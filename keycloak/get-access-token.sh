#!/usr/bin/env bash
# Gets an access token, saves it to output/jwt/<name>.token and prints who it is for.
#
#   keycloak/get-access-token.sh merchant-api MARKETPLACE-5      a merchant's backend (client credentials)
#   keycloak/get-access-token.sh user marketplace-5              a person, as when logging in to the back office
#   keycloak/get-access-token.sh user seller-5-1                 (password defaults to the one setup-keycloak.sh sets;
#   keycloak/get-access-token.sh user finance-ops                 pass a third argument to use another)
#
# Use it:  curl -H "Authorization: Bearer $(cat keycloak/output/jwt/marketplace-5.token)" ...
# Person tokens last 5 minutes (get a fresh one), merchant API tokens 10 hours.
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak.payment.svc.cluster.local:8080}"
TOKEN_URL="$KEYCLOAK_URL/realms/ecommerce-platform/protocol/openid-connect/token"
OUTPUT_DIR="$(cd "$(dirname "$0")" && pwd)/output"

usage() {
  sed -n '2,12p' "$0" >&2
  exit 1
}

kind="${1:-}"
name="${2:-}"
if [[ -z "$kind" || -z "$name" ]]; then
  usage
fi

case "$kind" in
  merchant-api)
    client="merchant-api-$name"
    # seed merchants have a fixed local secret (keycloak/realm/merchants-seed.json)
    secret=$(jq -r --arg c "$client" '.clients[] | select(.clientId == $c) | .secret' "$(dirname "$0")/realm/merchants-seed.json")
    if [[ -z "$secret" ]]; then
      echo "No seed merchant $name in keycloak/realm/merchants-seed.json" >&2
      exit 1
    fi
    response=$(curl -sS -d grant_type=client_credentials -d client_id="$client" -d client_secret="$secret" "$TOKEN_URL")
    ;;
  user)
    password="${3:-}"
    if [[ -z "$password" ]]; then
      case "$name" in
        support-ops) password=support123 ;;
        finance-ops) password=finance123 ;;
        backoffice-admin) password=admin123 ;;
        seller-*) password=seller123 ;;
        *) password=merchant123 ;;
      esac
    fi
    response=$(curl -sS -d grant_type=password -d client_id=backoffice-ui -d username="$name" -d password="$password" "$TOKEN_URL")
    ;;
  *)
    usage
    ;;
esac

token=$(echo "$response" | jq -r '.access_token // empty')
if [[ -z "$token" ]]; then
  echo "No token: $(echo "$response" | jq -r '.error_description // .error // .' 2>/dev/null)" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR/jwt"
file="$OUTPUT_DIR/jwt/$name.token"
echo "$token" > "$file"

# the payload is the middle part of the JWT, base64url without padding
payload=$(echo "$token" | cut -d. -f2 | tr '_-' '/+')
while (( ${#payload} % 4 != 0 )); do payload="$payload="; done
echo "$payload" | base64 -d 2>/dev/null | jq '{azp, merchant_id, seller_id, roles: .realm_access.roles, expires: (.exp | todate)}'
echo "saved to $file" >&2
