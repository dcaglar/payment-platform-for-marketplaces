#!/bin/bash
set -e

OUTPUT_DIR="$(dirname "$0")/output"
SECRETS_FILE="${OUTPUT_DIR}/secrets.txt"
JWT_DIR="${OUTPUT_DIR}/jwt"
REALM="ecommerce-platform"

# Wait until Keycloak is reachable to avoid provisioning race conditions
wait_for_keycloak() {
  local endpoint="$KC_URL/realms/$REALM/.well-known/openid-configuration"
  local attempt=1
  local max_attempts=30

  while ! curl -sf --max-time 2 "$endpoint" >/dev/null 2>&1; do
    if (( attempt == 1 )); then
      echo "⏳ Waiting for Keycloak to become ready at $endpoint ..."
    fi

    if (( attempt >= max_attempts )); then
      echo "❌ Keycloak is not reachable after $max_attempts attempts."
      echo "   Tried: $endpoint"
      exit 1
    fi

    sleep 1
    attempt=$((attempt + 1))
  done

  if (( attempt > 1 )); then
    echo "✅ Keycloak is reachable. Proceeding with token request."
  fi
}

# Default to SELLER-1-1, can be overridden
SELLER_ID="$(echo "${1:-SELLER-1-1}" | xargs)"
KC_URL_OVERRIDE="${2:-}"
TTL_HOURS="${3:-}"

if [[ -n "$KC_URL_OVERRIDE" ]]; then
  KC_URL="$KC_URL_OVERRIDE"
fi

KC_URL="${KC_URL:-http://keycloak.payment.svc.cluster.local:8080}"

# Ensure Keycloak is ready before requesting a token
wait_for_keycloak

# Ensure output directories exist
mkdir -p "$JWT_DIR"

SANITIZED_SELLER_ID=$(printf '%s' "$SELLER_ID" | tr -c 'A-Za-z0-9._:-' '_')

# Determine client ID based on seller ID
CLIENT_ID="seller-api-${SELLER_ID}"

# Extract client secret from secrets file
SECRET_ENV_VAR="SELLER_API_${SELLER_ID}_CLIENT_SECRET"
CLIENT_SECRET=$(grep "^${SECRET_ENV_VAR}=" "$SECRETS_FILE" 2>/dev/null | cut -d= -f2 | tr -d '\r\n' || echo "")

if [ -z "$CLIENT_SECRET" ]; then
  echo "❌ Could not find ${SECRET_ENV_VAR} in $SECRETS_FILE"
  echo "💡 Make sure you've run ./keycloak/provision-keycloak.sh first"
  echo "💡 Every seller in central-db has a client seller-api-<SELLER_ID>, e.g. seller-api-SELLER-5-1 (run provision-keycloak.sh first)"
  exit 1
fi

TOKEN_ENDPOINT="$KC_URL/realms/$REALM/protocol/openid-connect/token"
ACCESS_TOKEN_FILE="${JWT_DIR}/seller-api-${SANITIZED_SELLER_ID}.token"
CLAIMS_FILE="${JWT_DIR}/seller-api-${SANITIZED_SELLER_ID}.claims.json"

echo "🔐 Requesting JWT with SELLER_API role for seller '$SELLER_ID' from Keycloak at $TOKEN_ENDPOINT..."
declare -a EXTRA_ARGS=()
TTL_MESSAGE=""
if [[ -n "$TTL_HOURS" ]]; then
  if [[ "$TTL_HOURS" =~ ^[0-9]+$ ]]; then
    LIFESPAN_SECONDS=$(( TTL_HOURS * 3600 ))
    EXTRA_ARGS+=(-d "access_token_lifespan=$LIFESPAN_SECONDS")
    TTL_MESSAGE="$TTL_HOURS"
  else
    echo "⚠️  TTL hours must be an integer; ignoring '$TTL_HOURS'"
  fi
fi

RESPONSE=$(curl -s -X POST "$TOKEN_ENDPOINT" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=$CLIENT_ID" \
  -d "client_secret=$CLIENT_SECRET" \
  "${EXTRA_ARGS[@]}")

ACCESS_TOKEN=$(echo "$RESPONSE" | jq -r .access_token)

if [ -z "$ACCESS_TOKEN" ] || [ "$ACCESS_TOKEN" = "null" ]; then
  echo "❌ Failed to obtain access token:"
  echo "$RESPONSE"
  echo ""
  echo "💡 Make sure you've run ./keycloak/provision-keycloak.sh first"
  echo "💡 Every seller in central-db has a client seller-api-<SELLER_ID>, e.g. seller-api-SELLER-5-1 (run provision-keycloak.sh first)"
  exit 1
fi

# Extract seller_id from token (optional, for verification)
# Use Python for reliable JWT decoding (handles URL-safe base64 and padding)
SELLER_ID_IN_TOKEN=$(python3 -c "
import base64, json, sys
token = '$ACCESS_TOKEN'
try:
    parts = token.split('.')
    if len(parts) > 1:
        payload = parts[1]
        payload += '=' * (4 - len(payload) % 4)
        data = json.loads(base64.urlsafe_b64decode(payload))
        print(data.get('seller_id', ''))
except:
    pass
" 2>/dev/null || echo "")

if [ -n "$SELLER_ID_IN_TOKEN" ]; then
  echo "✅ Token contains seller_id: $SELLER_ID_IN_TOKEN"
  if [ "$SELLER_ID_IN_TOKEN" != "$SELLER_ID" ]; then
    echo "⚠️  Warning: Token seller_id ($SELLER_ID_IN_TOKEN) doesn't match requested ($SELLER_ID)"
  fi
else
  echo "⚠️  Note: seller_id claim not found in token. Make sure provisioning script configured the mapper."
fi

# Verify SELLER_API role is present
SELLER_API_ROLE=$(python3 -c "
import base64, json, sys
token = '$ACCESS_TOKEN'
try:
    parts = token.split('.')
    if len(parts) > 1:
        payload = parts[1]
        payload += '=' * (4 - len(payload) % 4)
        data = json.loads(base64.urlsafe_b64decode(payload))
        roles = data.get('realm_access', {}).get('roles', [])
        if 'SELLER_API' in roles:
            print('SELLER_API')
except:
    pass
" 2>/dev/null || echo "")

if [ -n "$SELLER_API_ROLE" ]; then
  echo "✅ Token contains SELLER_API role"
else
  echo "⚠️  Warning: SELLER_API role not found in token. Make sure role was assigned to service account."
fi

echo "$ACCESS_TOKEN" > "$ACCESS_TOKEN_FILE"
echo "✅ Token saved to $ACCESS_TOKEN_FILE"

if command -v python3 >/dev/null 2>&1; then
  python3 - <<PY > "$CLAIMS_FILE"
import base64, json, sys
token = "$ACCESS_TOKEN"
try:
    parts = token.split(".")
    if len(parts) >= 2:
        payload = parts[1] + "=" * (-len(parts[1]) % 4)
        data = json.loads(base64.urlsafe_b64decode(payload))
        json.dump(data, sys.stdout, indent=2, sort_keys=True)
    else:
        sys.stdout.write("{}")
except Exception:
    sys.stdout.write("{}")
PY
  echo "📝 Claims saved to $CLAIMS_FILE"
else
  echo "{}" > "$CLAIMS_FILE"
  echo "⚠️  python3 not found; wrote empty claims file to $CLAIMS_FILE"
fi

if command -v jq >/dev/null 2>&1; then
  ROLES=$(jq -r '.realm_access.roles // [] | join(",")' "$CLAIMS_FILE" 2>/dev/null)
  if [[ -n "$ROLES" ]]; then
    echo "🛡️  Realm roles: $ROLES"
  fi
fi

if [[ -n "$TTL_MESSAGE" ]]; then
  echo "⏱️  Requested token lifespan: ${TTL_MESSAGE}h"
fi

echo "💡 Use this token to query the seller's own balance: GET /api/v1/balances/me"
echo "💡 This token uses Client Credentials flow (M2M) with SELLER_API role"

