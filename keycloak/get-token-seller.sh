#!/bin/bash
set -e

OUTPUT_DIR="$(dirname "$0")/output"
JWT_DIR="${OUTPUT_DIR}/jwt"
REALM="ecommerce-platform"

# Default to seller-1-1 (provisioned for SELLER-1-1), can be overridden
DEFAULT_USERNAME="seller-1-1"
DEFAULT_PASSWORD="seller123"
USERNAME="$(echo "${1:-$DEFAULT_USERNAME}" | xargs)"
PASSWORD="${2:-$DEFAULT_PASSWORD}"
KC_URL_OVERRIDE="${3:-}"
TTL_HOURS="${4:-}"

if [[ -n "$KC_URL_OVERRIDE" ]]; then
  KC_URL="$KC_URL_OVERRIDE"
fi

KC_URL="${KC_URL:-http://keycloak.payment.svc.cluster.local:8080}"

SANITIZED_USERNAME=$(printf '%s' "$USERNAME" | tr -c 'A-Za-z0-9._:-' '_')
BASE_NAME="seller-${SANITIZED_USERNAME}"
ACCESS_TOKEN_FILE="${JWT_DIR}/${BASE_NAME}.token"
CLAIMS_FILE="${JWT_DIR}/${BASE_NAME}.claims.json"

# Ensure output directories exist
mkdir -p "$JWT_DIR"

# Wait until Keycloak is reachable to avoid race conditions right after provisioning
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

# Ensure Keycloak is ready before requesting a token
wait_for_keycloak

TOKEN_ENDPOINT="$KC_URL/realms/$REALM/protocol/openid-connect/token"
CLIENT_ID="customer-area-frontend"  # OIDC client with both Authorization Code and Direct Access Grants

echo "🔐 Requesting JWT with SELLER role for user '$USERNAME' from Keycloak at $TOKEN_ENDPOINT..."
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
  -d "grant_type=password" \
  -d "client_id=$CLIENT_ID" \
  -d "username=$USERNAME" \
  -d "password=$PASSWORD" \
  "${EXTRA_ARGS[@]}")

ACCESS_TOKEN=$(echo "$RESPONSE" | jq -r .access_token)

if [ -z "$ACCESS_TOKEN" ] || [ "$ACCESS_TOKEN" = "null" ]; then
  echo "❌ Failed to obtain access token:"
  echo "$RESPONSE"
  echo ""
  echo "💡 Make sure you've run ./keycloak/provision-keycloak.sh first"
  echo "💡 Available test users: seller-111, seller-222, seller-333 (password: seller123)"
  exit 1
fi

# Extract seller_id from token (optional, for verification)
# Decode JWT payload (second part)
SELLER_ID=$(echo "$ACCESS_TOKEN" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq -r '.seller_id // empty' 2>/dev/null || echo "")
SELLER_ID=$(echo "$SELLER_ID" | xargs)
if [ -n "$SELLER_ID" ]; then
  echo "✅ Token contains seller_id: $SELLER_ID"
else
  echo "⚠️  Note: seller_id claim not found in token. Make sure provisioning script configured the mapper."
fi

# Write token
echo "$ACCESS_TOKEN" > "$ACCESS_TOKEN_FILE"

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
else
  echo "{}" > "$CLAIMS_FILE"
  echo "⚠️  python3 not found; wrote empty claims file to $CLAIMS_FILE"
fi

# Rename files based on seller_id claim if available
if [[ -n "$SELLER_ID" ]]; then
  SANITIZED_SELLER_ID=$(printf '%s' "$SELLER_ID" | tr -c 'A-Za-z0-9._:-' '_')
  TARGET_BASE="seller-${SANITIZED_SELLER_ID}"
  TARGET_TOKEN="${JWT_DIR}/${TARGET_BASE}.token"
  TARGET_CLAIMS="${JWT_DIR}/${TARGET_BASE}.claims.json"
  if [[ "$TARGET_TOKEN" != "$ACCESS_TOKEN_FILE" ]]; then
    mv "$ACCESS_TOKEN_FILE" "$TARGET_TOKEN"
    mv "$CLAIMS_FILE" "$TARGET_CLAIMS"
    ACCESS_TOKEN_FILE="$TARGET_TOKEN"
    CLAIMS_FILE="$TARGET_CLAIMS"
  fi
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

echo "✅ Token saved to $ACCESS_TOKEN_FILE"
echo "📝 Claims saved to $CLAIMS_FILE"
echo "💡 Use this token to query your own balance: GET /api/v1/balances/me"

