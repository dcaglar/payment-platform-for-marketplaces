#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

export KEYCLOAK_IP=$(kubectl get svc -n payment keycloak -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
export KC_URL="http://${KEYCLOAK_IP}:8080"
OUTPUT_DIR="$REPO_ROOT/keycloak/output"
SECRETS_FILE="${OUTPUT_DIR}/secrets.txt"
JWT_DIR="${OUTPUT_DIR}/jwt"
TOKEN_BASENAME="payment-service.token"
CLAIMS_BASENAME="payment-service.claims.json"
ACCESS_TOKEN_FILE="${JWT_DIR}/${TOKEN_BASENAME}"
CLAIMS_FILE="${JWT_DIR}/${CLAIMS_BASENAME}"
CLIENT_ID="payment-service"
REALM="ecommerce-platform"

# Optional CLI override for Keycloak URL
CLI_KC_URL="${1:-}"
TTL_HOURS="${2:4}"

if [[ -n "$CLI_KC_URL" ]]; then
  KC_URL="$CLI_KC_URL"
fi

KC_URL="${KC_URL:-http://keycloak.payment.svc.cluster.local:8080}"

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

# Extract client secret
CLIENT_SECRET=$(grep PAYMENT_SERVICE_CLIENT_SECRET= "$SECRETS_FILE" | cut -d= -f2 | tr -d '\r\n')

if [ -z "$CLIENT_SECRET" ]; then
  echo "❌ Could not find PAYMENT_SERVICE_CLIENT_SECRET in $SECRETS_FILE"
  exit 1
fi

TOKEN_ENDPOINT="$KC_URL/realms/$REALM/protocol/openid-connect/token"

# Optional TTL scaling
TOKEN_ENDPOINT="$KC_URL/realms/$REALM/protocol/openid-connect/token"

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

echo "🔐 Requesting JWT from Keycloak at $TOKEN_ENDPOINT..."
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
except Exception as exc:
    sys.stdout.write("{}")
PY
  echo "📝 Claims saved to $CLAIMS_FILE"
else
  echo "{}" > "$CLAIMS_FILE"
  echo "⚠️  python3 not found; wrote empty claims file to $CLAIMS_FILE"
fi

if [[ -n "$TTL_MESSAGE" ]]; then
  echo "⏱️  Requested token lifespan: ${TTL_MESSAGE}h"
fi