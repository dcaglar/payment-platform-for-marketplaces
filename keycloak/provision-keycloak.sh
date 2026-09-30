#!/usr/bin/env bash
set -euo pipefail

# --- Configurable Vars ---
# Default behavior: try localhost first (for port-forwarding), fallback to cluster-internal
# Override with KEYCLOAK_URL environment variable to force a specific URL
REALM="ecommerce-platform"
ADMIN_USER="admin"
ADMIN_PASS="adminpassword"
OUTPUT_DIR="$(dirname "$0")/output"
export KEYCLOAK_IP=$(kubectl get svc -n payment keycloak -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
export KEYCLOAK_URL="http://${KEYCLOAK_IP}:8080"
#export KEYCLOAK_IP=$(kubectl get svc -n payment keycloak -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

mkdir -p "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR/jwt"
# Utility to create or recreate a user and assign roles
create_internal_user() {
  local username="$1"
  local password="$2"
  shift 2
  local roles=("$@")

  log "  Creating/updating internal user '$username' with roles=${roles[*]}..."

  local user_id
  user_id=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/users?username=$username" \
    -H "Authorization: Bearer $KC_TOKEN" | jq -r '.[0].id // empty' 2>/dev/null || echo "")

  if [[ -n "$user_id" && "$user_id" != "null" ]]; then
    log "   Existing user found (id=$user_id). Deleting for clean reprovision..."
    curl -sf -X DELETE "$KEYCLOAK_URL/admin/realms/$REALM/users/$user_id" \
      -H "Authorization: Bearer $KC_TOKEN" >/dev/null 2>&1 || log "   ⚠️ Failed to delete user $username (id=$user_id)"
    sleep 0.3
    user_id=""
  fi

  if [[ -z "$user_id" || "$user_id" == "null" ]]; then
    local create_response
    create_response=$(curl -sf -w "%{http_code}" -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users" \
      -H "Authorization: Bearer $KC_TOKEN" \
      -H "Content-Type: application/json" \
      -d '{
        "username":"'"$username"'",
        "enabled":true,
        "credentials":[{"type":"password","value":"'"$password"'","temporary":false}]
      }' -o /dev/null 2>/dev/null || echo "000")

    if [[ "$create_response" != "201" ]]; then
      log "   ⚠️ User creation returned HTTP $create_response (may already exist)"
    fi

    user_id=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/users?username=$username" \
      -H "Authorization: Bearer $KC_TOKEN" | jq -r '.[0].id // empty' 2>/dev/null || echo "")
  fi

  if [[ -n "$user_id" && "$user_id" != "null" ]]; then
    curl -sf -X PUT "$KEYCLOAK_URL/admin/realms/$REALM/users/$user_id/reset-password" \
      -H "Authorization: Bearer $KC_TOKEN" \
      -H "Content-Type: application/json" \
      -d '{"type":"password","value":"'"$password"'","temporary":false}' >/dev/null 2>&1 || true

    for role_name in "${roles[@]}"; do
      local role_obj
      role_obj=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/roles/$role_name" \
        -H "Authorization: Bearer $KC_TOKEN" 2>/dev/null || echo "")
      if [[ -n "$role_obj" && "$role_obj" != "null" ]]; then
        curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users/$user_id/role-mappings/realm" \
          -H "Authorization: Bearer $KC_TOKEN" \
          -H "Content-Type: application/json" \
          -d "[$role_obj]" >/dev/null 2>&1 || true
        log "   ✅ Assigned role $role_name to $username"
      else
        log "   ⚠️ Role $role_name not found when assigning to $username"
      fi
    done
    log "  ✅ Internal user $username provisioned"
  else
    log "  ⚠️ Could not create or find user $username"
  fi
}


log() { echo "[$(date +'%H:%M:%S')] $*" >&2; }

# Auto-detect Keycloak URL if not explicitly set
# NOTE: The URL used for provisioning doesn't affect JWT issuer - that's controlled by
# Keycloak's KC_HOSTNAME configuration (set in keycloak-values-local.yaml)
# Default to localhost assuming port-forwarding is running
if [[ -z "${KEYCLOAK_URL:-}" ]]; then
  log "🔍 Using cluster-internal Keycloak URL..."
  KEYCLOAK_URL="http://keycloak.payment.svc.cluster.local"

else
  log "   Using explicit KEYCLOAK_URL: $KEYCLOAK_URL"
fi

# --- Authenticate as Admin ---
log "🔐 Authenticating as admin..."
log "   Using Keycloak URL: $KEYCLOAK_URL"

# Test connectivity first
if ! curl -sf --max-time 5 "$KEYCLOAK_URL/realms/master" >/dev/null 2>&1; then
  log "❌ Cannot reach Keycloak at $KEYCLOAK_URL"
  log "   Make sure Keycloak is running and accessible."
  log "   You can override with: KEYCLOAK_URL=<your-url> ./keycloak/provision-keycloak.sh"
  exit 1
fi

# Get admin token with better error handling
KC_TOKEN=$(curl -f -s --max-time 10 \
  -d "client_id=admin-cli" \
  -d "username=$ADMIN_USER" \
  -d "password=$ADMIN_PASS" \
  -d "grant_type=password" \
  "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" 2>/dev/null \
  | jq -r '.access_token // empty' 2>/dev/null || echo "")

if [[ -z "$KC_TOKEN" || "$KC_TOKEN" == "null" ]]; then
  log "❌ Failed to get admin token."
  log "   Check that Keycloak is running and credentials are correct."
  exit 1
fi

log "✅ Admin token obtained"

# --- Create Realm ---
log "🛠️ Ensuring realm '$REALM' exists..."
curl -sf -X POST "$KEYCLOAK_URL/admin/realms" \
  -H "Authorization: Bearer $KC_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"realm":"'"$REALM"'","enabled":true}' || log "ℹ️ Realm may already exist"

# --- Create Roles ---
log "🛠️ Creating roles..."
for role in "payment:write" "FINANCE" "ADMIN" "SELLER" "SELLER_API" "MERCHANT"; do
  log "  Creating role '$role'..."
  curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/roles" \
    -H "Authorization: Bearer $KC_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name":"'"$role"'"}' || log "ℹ️ Role $role may already exist"
done

ROLE_NAME="payment:write"

# --- Functions for Client Management ---
get_client_id() {
  local name="$1"
  curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$name" \
    -H "Authorization: Bearer $KC_TOKEN" | jq -r '.[0].id'
}

create_client() {
  local name="$1"
  log "🛠️ Ensuring client '$name' exists..."
  curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients" \
    -H "Authorization: Bearer $KC_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"clientId":"'"$name"'","enabled":true,"protocol":"openid-connect","publicClient":false,"serviceAccountsEnabled":true,"directAccessGrantsEnabled":false}' \
    || true
  local client_id
  client_id=$(get_client_id "$name")
  if [[ -z "$client_id" || "$client_id" == "null" ]]; then
    log "❌ Could not find or create client $name. Exiting."
    exit 2
  fi
  # Set client to confidential and set long token lifespan
  curl -sf -X PUT "$KEYCLOAK_URL/admin/realms/$REALM/clients/$client_id" \
    -H "Authorization: Bearer $KC_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"publicClient":false,"serviceAccountsEnabled":true,"directAccessGrantsEnabled":false,"attributes":{"access.token.lifespan":"2592000"}}' || true
  echo "$client_id"
}

get_client_secret() {
  local client_id="$1"
  curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/clients/$client_id/client-secret" \
    -H "Authorization: Bearer $KC_TOKEN" | jq -r .value
}

assign_role_to_service_account() {
  local client_id="$1"
  local role_name="$2"
  # Get service account user id
  local sa_id
  sa_id=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/clients/$client_id/service-account-user" \
    -H "Authorization: Bearer $KC_TOKEN" | jq -r .id)
  if [[ -z "$sa_id" || "$sa_id" == "null" ]]; then
    log "❗ Service account user for client $client_id not found."
    return 1
  fi
  # Get role as object
  local role_obj
  role_obj=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/roles/$role_name" \
    -H "Authorization: Bearer $KC_TOKEN")
  if [[ -z "$role_obj" || "$role_obj" == "null" ]]; then
    log "❗ Role $role_name not found."
    return 1
  fi
  # Assign role
  local assign_result
  assign_result=$(curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users/$sa_id/role-mappings/realm" \
    -H "Authorization: Bearer $KC_TOKEN" \
    -H "Content-Type: application/json" \
    -d "[$role_obj]" -w "%{http_code}" -o /dev/null 2>/dev/null || echo "000")
  
  # Verify role was assigned by checking current roles
  sleep 0.3
  local has_role
  has_role=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/users/$sa_id/role-mappings/realm" \
    -H "Authorization: Bearer $KC_TOKEN" | jq -r ".[] | select(.name==\"$role_name\") | .name" | head -1)
  
  if [[ "$has_role" == "$role_name" ]]; then
    log "✅ Role $role_name assigned to service-account-$client_id"
  else
    log "⚠️ Role $role_name assignment may have failed - verify manually in Keycloak"
  fi
}

# Sellers come from central-db (account_directory, SELLER_PAYABLE), so every seller that has an
# account gets an identity; nothing is hardcoded here. Read-only query inside the central-db pod.
list_sellers() {
  kubectl exec -n payment central-db-postgresql-0 -c postgresql -- sh -c \
    'PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")" psql -U postgres -d central-db -At -c "SELECT DISTINCT sub_entity_id FROM account_directory WHERE account_type = '"'"'SELLER_PAYABLE'"'"' AND sub_entity_id IS NOT NULL ORDER BY 1"'
}

# Merchants: every master_account_code that owns a merchant payable account
list_merchants() {
  kubectl exec -n payment central-db-postgresql-0 -c postgresql -- sh -c \
    'PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")" psql -U postgres -d central-db -At -c "SELECT DISTINCT master_account_code FROM account_directory WHERE account_type IN ('"'"'MERCHANT_DIRECT_PAYABLE'"'"', '"'"'MERCHANT_COMMISSION_PAYABLE'"'"') ORDER BY 1"'
}

SELLERS=$(list_sellers || true)
if [[ -z "$SELLERS" ]]; then
  log "❌ Could not read sellers from central-db (is central-db-postgresql-0 running?)."
  exit 3
fi
log "📋 Sellers in central-db: $(echo "$SELLERS" | wc -l | tr -d ' ')"
MERCHANTS=$(list_merchants || true)
log "📋 Merchants in central-db: $(echo "$MERCHANTS" | grep -c . || true)"

# --- Provision Clients & Roles ---
# Use bash 3-compatible approach (macOS default bash version)
# Format: client_name:secret_env_var
CLIENTS=(
  "order-service:ORDER_SERVICE_CLIENT_SECRET"
  "payment-service:PAYMENT_SERVICE_CLIENT_SECRET"
  "finance-service:FINANCE_SERVICE_CLIENT_SECRET"
)

SECRETS_OUT="$OUTPUT_DIR/secrets.txt"
echo -n > "$SECRETS_OUT"
for client_entry in "${CLIENTS[@]}"; do
  # Split client_name:secret_env_var
  IFS=':' read -r client secret_env_var <<< "$client_entry"
  
  client_id=$(create_client "$client")
  secret=$(get_client_secret "$client_id")
  log "🔑 $client secret: $secret"
  
  # Assign payment:write to payment-service and order-service
  if [[ "$client" == "payment-service" || "$client" == "order-service" ]]; then
    assign_role_to_service_account "$client_id" "$ROLE_NAME"
  fi
  
  # Assign FINANCE role to finance-service (for balance queries)
  if [[ "$client" == "finance-service" ]]; then
    assign_role_to_service_account "$client_id" "FINANCE"
  fi
  
  echo "${secret_env_var}=$secret" >> "$SECRETS_OUT"
done

# --- Create Customer Area Frontend Client (Case 1: Seller user authentication) ---
# Supports both OIDC Authorization Code flow (production) and Direct Access Grants (testing)
log "🛠️ Creating customer-area-frontend client (Case 1: Seller user via frontend)..."
CUSTOMER_AREA_CLIENT_ID="customer-area-frontend"
CUSTOMER_AREA_CLIENT_KC_ID=$(get_client_id "$CUSTOMER_AREA_CLIENT_ID")
if [[ -z "$CUSTOMER_AREA_CLIENT_KC_ID" || "$CUSTOMER_AREA_CLIENT_KC_ID" == "null" ]]; then
  CUSTOMER_AREA_CLIENT_KC_ID=$(curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients" \
    -H "Authorization: Bearer $KC_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "clientId":"'"$CUSTOMER_AREA_CLIENT_ID"'",
      "enabled":true,
      "protocol":"openid-connect",
      "publicClient":true,
      "serviceAccountsEnabled":false,
      "standardFlowEnabled":true,
      "directAccessGrantsEnabled":true,
      "implicitFlowEnabled":false,
      "redirectUris":["http://localhost:*","https://customer-area.example.com/*"],
      "webOrigins":["*"]
    }' | jq -r '.id // empty' || echo "")
  if [[ -n "$CUSTOMER_AREA_CLIENT_KC_ID" && "$CUSTOMER_AREA_CLIENT_KC_ID" != "null" ]]; then
    log "  ✅ Created customer-area-frontend client"
  fi
else
  log "  ℹ️ Customer-area-frontend client already exists"
fi

# Configure protocol mapper for seller_id on customer-area-frontend
if [[ -n "$CUSTOMER_AREA_CLIENT_KC_ID" && "$CUSTOMER_AREA_CLIENT_KC_ID" != "null" ]]; then
  log "🛠️ Configuring seller_id mapper for customer-area-frontend..."
  curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CUSTOMER_AREA_CLIENT_KC_ID/protocol-mappers/models" \
    -H "Authorization: Bearer $KC_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "name": "seller-id-mapper",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-usermodel-attribute-mapper",
      "config": {
        "user.attribute": "seller_id",
        "claim.name": "seller_id",
        "jsonType.label": "String",
        "id.token.claim": "true",
        "access.token.claim": "true",
        "userinfo.token.claim": "true"
      }
    }' || log "  ℹ️ Mapper may already exist"
fi

# --- Create Backoffice Frontend Client (Case 2: Finance/Admin user authentication) ---
# Supports both OIDC Authorization Code flow (production) and Direct Access Grants (testing)
log "🛠️ Creating backoffice-ui client (Case 2: Finance/Admin user via backoffice)..."
BACKOFFICE_CLIENT_ID="backoffice-ui"
BACKOFFICE_CLIENT_KC_ID=$(get_client_id "$BACKOFFICE_CLIENT_ID")
if [[ -z "$BACKOFFICE_CLIENT_KC_ID" || "$BACKOFFICE_CLIENT_KC_ID" == "null" ]]; then
  BACKOFFICE_CLIENT_KC_ID=$(curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients" \
    -H "Authorization: Bearer $KC_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "clientId":"'"$BACKOFFICE_CLIENT_ID"'",
      "enabled":true,
      "protocol":"openid-connect",
      "publicClient":true,
      "serviceAccountsEnabled":false,
      "standardFlowEnabled":true,
      "directAccessGrantsEnabled":true,
      "implicitFlowEnabled":false,
      "redirectUris":["http://localhost:*","https://backoffice.example.com/*"],
      "webOrigins":["*"]
    }' | jq -r '.id // empty' || echo "")
  if [[ -n "$BACKOFFICE_CLIENT_KC_ID" && "$BACKOFFICE_CLIENT_KC_ID" != "null" ]]; then
    log "  ✅ Created backoffice-ui client"
  fi
else
  log "  ℹ️ Backoffice-ui client already exists"
fi

# --- Create Seller API Clients (Case 3: Machine-to-Machine seller API) ---
# One client per seller, using Client Credentials flow with SELLER_API role and a fixed seller_id claim
log "🛠️ Creating seller API clients (Case 3: M2M seller API)..."
# One client_credentials client that acts for one owner (a seller or a merchant): role + a fixed
# owner claim (seller_id / merchant_id) in every token, secret written to secrets.txt.
# Args: client_id role claim_name owner_id secret_var
create_owner_api_client() {
  local client_id="$1"
  local role="$2"
  local claim_name="$3"
  local owner_id="$4"
  local secret_var="$5"
  local mapper_name="${claim_name//_/-}-mapper"

  log "  Creating API client $client_id ($role, $claim_name=$owner_id)..."

  local client_kc_id
  client_kc_id=$(get_client_id "$client_id")

  if [[ -z "$client_kc_id" || "$client_kc_id" == "null" ]]; then
    curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients" \
      -H "Authorization: Bearer $KC_TOKEN" \
      -H "Content-Type: application/json" \
      -d '{
        "clientId":"'"$client_id"'",
        "enabled":true,
        "protocol":"openid-connect",
        "publicClient":false,
        "serviceAccountsEnabled":true,
        "directAccessGrantsEnabled":false,
        "standardFlowEnabled":false,
        "attributes":{"access.token.lifespan":"2592000"}
      }' >/dev/null || true
    # Keycloak answers 201 with an empty body (the id is only in the Location header): look it up by name
    client_kc_id=$(get_client_id "$client_id")

    if [[ -n "$client_kc_id" && "$client_kc_id" != "null" ]]; then
      log "  ✅ Created API client: $client_id"
    fi
  else
    log "  ℹ️ API client $client_id already exists"
  fi

  if [[ -z "$client_kc_id" || "$client_kc_id" == "null" ]]; then
    log "  ⚠️ Could not create or find client $client_id"
    return 0
  fi

  # Always get the secret and write it to the file (even if the client already existed)
  local secret
  secret=$(get_client_secret "$client_kc_id")
  if [[ -n "$secret" && "$secret" != "null" ]]; then
    log "  🔑 $client_id secret written to secrets.txt"
    grep -v "^${secret_var}=" "$SECRETS_OUT" > "${SECRETS_OUT}.tmp" 2>/dev/null || true
    mv "${SECRETS_OUT}.tmp" "$SECRETS_OUT" 2>/dev/null || true
    echo "${secret_var}=$secret" >> "$SECRETS_OUT"
  fi

  assign_role_to_service_account "$client_kc_id" "$role"

  # Fixed owner claim in every token of this client
  local mapper_json='{
      "name": "'"$mapper_name"'",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-hardcoded-claim-mapper",
      "config": {
        "claim.value": "'"$owner_id"'",
        "claim.name": "'"$claim_name"'",
        "jsonType.label": "String",
        "id.token.claim": "true",
        "access.token.claim": "true",
        "userinfo.token.claim": "true"
      }
    }'
  local mapper_id
  mapper_id=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/clients/$client_kc_id/protocol-mappers/models" \
    -H "Authorization: Bearer $KC_TOKEN" | jq -r '.[] | select(.name=="'"$mapper_name"'") | .id' | head -1)

  if [[ -n "$mapper_id" && "$mapper_id" != "null" ]]; then
    echo "$mapper_json" | jq --arg id "$mapper_id" '. + {id: $id}' | curl -sf -X PUT \
      "$KEYCLOAK_URL/admin/realms/$REALM/clients/$client_kc_id/protocol-mappers/models/$mapper_id" \
      -H "Authorization: Bearer $KC_TOKEN" -H "Content-Type: application/json" -d @- >/dev/null 2>&1 \
      && log "  ✅ Mapper updated" || log "  ⚠️ Could not update mapper"
  else
    echo "$mapper_json" | curl -sf -X POST \
      "$KEYCLOAK_URL/admin/realms/$REALM/clients/$client_kc_id/protocol-mappers/models" \
      -H "Authorization: Bearer $KC_TOKEN" -H "Content-Type: application/json" -d @- >/dev/null 2>&1 \
      && log "  ✅ Mapper created" || log "  ⚠️ Could not create mapper"
  fi
}

create_seller_api_client() {
  local seller_id="$1"

  # Clients were called merchant-api-<seller> before; remove the old one so only seller-api-<seller> exists
  local legacy_kc_id
  legacy_kc_id=$(get_client_id "merchant-api-${seller_id}")
  if [[ -n "$legacy_kc_id" && "$legacy_kc_id" != "null" ]]; then
    curl -sf -X DELETE "$KEYCLOAK_URL/admin/realms/$REALM/clients/$legacy_kc_id" \
      -H "Authorization: Bearer $KC_TOKEN" >/dev/null 2>&1 && log "  🧹 Removed legacy client merchant-api-${seller_id}"
  fi

  create_owner_api_client "seller-api-${seller_id}" "SELLER_API" "seller_id" "$seller_id" "SELLER_API_${seller_id}_CLIENT_SECRET"
}

create_merchant_api_client() {
  local merchant_id="$1"
  create_owner_api_client "merchant-api-${merchant_id}" "MERCHANT" "merchant_id" "$merchant_id" "MERCHANT_API_${merchant_id}_CLIENT_SECRET"
}

# One seller API client per seller in central-db
for seller in $SELLERS; do
  create_seller_api_client "$seller"
done

# --- Merchant API clients: one per merchant, role MERCHANT, merchant_id claim ---
log "🛠️ Creating merchant API clients (role MERCHANT)..."
for merchant in $MERCHANTS; do
  create_merchant_api_client "$merchant"
done

# --- Legacy: Keep seller-client for backward compatibility (optional) ---
# This can be removed if you want to fully migrate to customer-area-frontend
log "🛠️ Creating legacy seller-client for backward compatibility..."
SELLER_CLIENT_ID="seller-client"
SELLER_CLIENT_KC_ID=$(get_client_id "$SELLER_CLIENT_ID")
if [[ -z "$SELLER_CLIENT_KC_ID" || "$SELLER_CLIENT_KC_ID" == "null" ]]; then
  SELLER_CLIENT_KC_ID=$(curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients" \
    -H "Authorization: Bearer $KC_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "clientId":"'"$SELLER_CLIENT_ID"'",
      "enabled":true,
      "protocol":"openid-connect",
      "publicClient":true,
      "serviceAccountsEnabled":false,
      "directAccessGrantsEnabled":true,
      "standardFlowEnabled":true
    }' | jq -r '.id // empty' || echo "")
  if [[ -n "$SELLER_CLIENT_KC_ID" && "$SELLER_CLIENT_KC_ID" != "null" ]]; then
    log "  ✅ Created legacy seller-client"
    # Configure seller_id mapper
    curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients/$SELLER_CLIENT_KC_ID/protocol-mappers/models" \
      -H "Authorization: Bearer $KC_TOKEN" \
      -H "Content-Type: application/json" \
      -d '{
        "name": "seller-id-mapper",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-usermodel-attribute-mapper",
        "config": {
          "user.attribute": "seller_id",
          "claim.name": "seller_id",
          "jsonType.label": "String",
          "id.token.claim": "true",
          "access.token.claim": "true",
          "userinfo.token.claim": "true"
        }
      }' || log "  ℹ️ Mapper may already exist"
  fi
else
  log "  ℹ️ Legacy seller-client already exists"
fi

# --- Create Test Users (for SELLER role testing) ---
log "🛠️ Creating test users for SELLER role..."
create_test_user() {
  local username="$1"
  local seller_id="$2"
  local password="$3"
  
  log "  Creating/updating user '$username' with seller_id=$seller_id..."
  
  # Get user ID if exists
  local user_id
  user_id=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/users?username=$username" \
    -H "Authorization: Bearer $KC_TOKEN" | jq -r '.[0].id // empty' 2>/dev/null || echo "")
  
  # If user already exists, delete it so we can recreate with managed credentials (avoids read-only errors)
  if [[ -n "$user_id" && "$user_id" != "null" ]]; then
    log "  Existing user found (id=$user_id). Deleting before re-creating to reset credentials..."
    curl -sf -X DELETE "$KEYCLOAK_URL/admin/realms/$REALM/users/$user_id" \
      -H "Authorization: Bearer $KC_TOKEN" >/dev/null 2>&1 || log "  ⚠️ Failed to delete user $username (id=$user_id)"
    sleep 0.3
    user_id=""
  fi

  # Create user if doesn't exist
  if [[ -z "$user_id" || "$user_id" == "null" ]]; then
    local create_response
    create_response=$(curl -sf -w "%{http_code}" -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users" \
      -H "Authorization: Bearer $KC_TOKEN" \
      -H "Content-Type: application/json" \
      -d '{
        "username":"'"$username"'",
        "enabled":true,
        "credentials":[{"type":"password","value":"'"$password"'","temporary":false}],
        "attributes":{"seller_id":["'"$seller_id"'"]}
      }' -o /dev/null 2>/dev/null || echo "000")
    
    if [[ "$create_response" != "201" ]]; then
      log "  ⚠️ User creation returned HTTP $create_response (may already exist)"
    fi
    
    # Get user ID after creation attempt
    user_id=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/users?username=$username" \
      -H "Authorization: Bearer $KC_TOKEN" | jq -r '.[0].id // empty' 2>/dev/null || echo "")
  fi
  
  # Update user if exists
  if [[ -n "$user_id" && "$user_id" != "null" ]]; then
    # Update password
    curl -sf -X PUT "$KEYCLOAK_URL/admin/realms/$REALM/users/$user_id/reset-password" \
      -H "Authorization: Bearer $KC_TOKEN" \
      -H "Content-Type: application/json" \
      -d '{"type":"password","value":"'"$password"'","temporary":false}' >/dev/null 2>&1 || true
    
    # Update seller_id attribute
    curl -sf -X PUT "$KEYCLOAK_URL/admin/realms/$REALM/users/$user_id" \
      -H "Authorization: Bearer $KC_TOKEN" \
      -H "Content-Type: application/json" \
      -d '{"attributes":{"seller_id":["'"$seller_id"'"]}}' >/dev/null 2>&1 || true
    
    log "  ✅ User $username updated"
  else
    log "  ⚠️ Could not create or find user $username"
    return 1
  fi
  
  # Assign SELLER role
  local role_obj
  role_obj=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/roles/SELLER" \
    -H "Authorization: Bearer $KC_TOKEN" 2>/dev/null || echo "")
  
  if [[ -n "$role_obj" && "$role_obj" != "null" ]]; then
    curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users/$user_id/role-mappings/realm" \
      -H "Authorization: Bearer $KC_TOKEN" \
      -H "Content-Type: application/json" \
      -d "[$role_obj]" >/dev/null 2>&1 || true
    log "  ✅ SELLER role assigned to $username"
  fi
}

# One login user per seller in central-db: SELLER-1-1 -> seller-1-1 (continue even if some fail)
for seller in $SELLERS; do
  username=$(echo "$seller" | tr '[:upper:]' '[:lower:]')
  create_test_user "$username" "$seller" "seller123" || log "  ⚠️ Failed to create $username, continuing..."
done

log "🛠️ Creating internal finance/admin users..."
create_internal_user "finance-ops" "finance123" "FINANCE" || log "  ⚠️ Failed to create finance-ops user"
create_internal_user "backoffice-admin" "admin123" "ADMIN" "FINANCE" || log "  ⚠️ Failed to create backoffice-admin user"


log "🔒 Client secrets written to $SECRETS_OUT"
log "📝 Per seller in central-db ($(echo "$SELLERS" | wc -l | tr -d ' ') sellers):"
log "   - user seller-x-y / seller123 (role SELLER, seller_id SELLER-X-Y)   -> get-token-seller.sh seller-x-y"
log "   - client seller-api-SELLER-X-Y (role SELLER_API, seller_id claim)   -> get-token-seller-api.sh SELLER-X-Y"
log "📝 Per merchant in central-db: client merchant-api-MARKETPLACE-N (role MERCHANT, merchant_id claim) -> get-token-merchant-api.sh MARKETPLACE-N"
log "📝 Internal users created:"
log "   - finance-ops / finance123 (roles: FINANCE)"
log "   - backoffice-admin / admin123 (roles: ADMIN, FINANCE)"
log "🎉 Keycloak provisioning complete!"

# Keep default access token lifespan moderate (e.g., 1 hour) so CLI overrides in get-token scripts
# can extend it as needed without touching realm config here.