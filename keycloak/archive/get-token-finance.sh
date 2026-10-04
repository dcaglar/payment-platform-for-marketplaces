#!/bin/bash
set -e

OUTPUT_DIR="$(dirname "$0")/output"
JWT_DIR="${OUTPUT_DIR}/jwt"
REALM="ecommerce-platform"
CLIENT_ID="backoffice-ui"

DEFAULT_USERNAME="finance-ops"
DEFAULT_PASSWORD="finance123"
USERNAME="$(echo "${1:-$DEFAULT_USERNAME}" | xargs)"
PASSWORD="${2:-$DEFAULT_PASSWORD}"
KC_URL_OVERRIDE="${3:-}"
TTL_HOURS="${4:-}"

if [[ -n "$KC_URL_OVERRIDE" ]]; then
  KC_URL="$KC_URL_OVERRIDE"
fi

KC_URL="${KC_URL:-http://keycloak.payment.svc.cluster.local:8080}"
SANITIZED_USERNAME=$(printf '%s' "$USERNAME" | tr -c 'A-Za-z0-9._:-' '_')
ACCESS_TOKEN_FILE="${JWT_DIR}/finance-${SANITIZED_USERNAME}.token"
CLAIMS_FILE="${JWT_DIR}/finance-${SANITIZED_USERNAME}.claims.json"

mkdir -p "$JWT_DIR"

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

wait_for_keycloak

TOKEN_ENDPOINT="$KC_URL/realms/$REALM/protocol/openid-connect/token"

echo "🔐 Requesting JWT with FINANCE role for user '$USERNAME' from Keycloak at $TOKEN_ENDPOINT..."
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
  exit 1
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

echo "💡 Use this token to query balance for any seller: GET /api/v1/balances/{sellerId}"

