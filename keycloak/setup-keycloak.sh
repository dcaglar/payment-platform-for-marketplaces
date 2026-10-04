#!/usr/bin/env bash
# Sets up Keycloak on the local cluster from two files (the e2e tests load the same two, the same way):
#
#   keycloak/realm/ecommerce-platform.json   the platform: permissions, the roles that bundle them
#                                            (MERCHANT SELLER SUPPORT FINANCE ADMIN), client backoffice-ui
#                                            (people log in; claims merchant_id / seller_id), staff users
#   keycloak/realm/merchants-seed.json       the seed merchants and sellers, GENERATED from
#                                            charts/central-db/seed/merchants.json (RealmSeedGenerator), like the
#                                            central-db seed: client merchant-api-<MERCHANT> per merchant,
#                                            users <merchant> / merchant123 and <seller> / seller123
#
# Safe to run again: it brings Keycloak to what the files say. Tokens: keycloak/get-access-token.sh
# Usage: keycloak/setup-keycloak.sh            (KEYCLOAK_URL overrides the address)
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak.payment.svc.cluster.local:8080}"
REALM="ecommerce-platform"
HERE="$(cd "$(dirname "$0")" && pwd)"
REALM_FILE="$HERE/realm/ecommerce-platform.json"
SEED_FILE="$HERE/realm/merchants-seed.json"

log() { echo "[$(date +%H:%M:%S)] $*" >&2; }

# A fresh admin token per call: admin tokens live 60 s.
admin_token() {
  curl -sf -d client_id=admin-cli -d username=admin -d password=adminpassword -d grant_type=password \
    "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" | jq -r .access_token
}

# kc METHOD PATH [JSON]   an admin API call; PATH is under /admin/realms. Fails on an error answer.
kc() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sSf -X "$method" -H "Authorization: Bearer $(admin_token)" "$KEYCLOAK_URL/admin/realms$path")
  if [[ -n "$body" ]]; then
    args+=(-H "Content-Type: application/json" -d "$body")
  fi
  curl "${args[@]}"
}

# ------------------------------------------------------------------ 1. the realm definition

log "Keycloak: $KEYCLOAK_URL"
# right after a deploy Keycloak needs a minute: wait up to 3 minutes for the admin login
for attempt in $(seq 1 36); do
  if [[ -n "$(admin_token 2>/dev/null || true)" ]]; then
    break
  fi
  if [[ "$attempt" == 36 ]]; then
    log "Cannot log in to Keycloak as admin at $KEYCLOAK_URL after 3 minutes (is it deployed?)"
    exit 1
  fi
  log "Waiting for Keycloak..."
  sleep 5
done

if curl -sf -o /dev/null -H "Authorization: Bearer $(admin_token)" "$KEYCLOAK_URL/admin/realms/$REALM"; then
  log "Realm exists: loading $REALM_FILE into it (partial import, replacing what changed)"
  kc POST "/$REALM/partialImport" "$(jq '. + {ifResourceExists: "OVERWRITE"}' "$REALM_FILE")" >/dev/null
else
  log "Creating realm from $REALM_FILE"
  kc POST "" "$(cat "$REALM_FILE")" >/dev/null
fi

# ------------------------------------------------------------------ 2. the seed merchants and sellers

log "Loading $SEED_FILE (partial import)"
kc POST "/$REALM/partialImport" "$(cat "$SEED_FILE")" >/dev/null

# ------------------------------------------------------------------ 3. what the old setup created and we no longer use

log "Removing clients and roles of the old setup"
old_clients="payment-service order-service finance-service customer-area-frontend seller-client backoffice-admin"
for seller in $(jq -r '.users[].attributes.seller_id[0] // empty' "$SEED_FILE"); do
  old_clients="$old_clients seller-api-$seller"
done
for old in $old_clients; do
  id=$(kc GET "/$REALM/clients?clientId=$old" | jq -r '.[0].id // empty')
  if [[ -n "$id" ]]; then
    kc DELETE "/$REALM/clients/$id" >/dev/null
    log "  removed client $old"
  fi
done
if curl -sf -o /dev/null -H "Authorization: Bearer $(admin_token)" "$KEYCLOAK_URL/admin/realms/$REALM/roles/SELLER_API"; then
  kc DELETE "/$REALM/roles/SELLER_API" >/dev/null
  log "  removed role SELLER_API"
fi

log "Done. Try: keycloak/get-access-token.sh merchant-api MARKETPLACE-5   |   keycloak/get-access-token.sh user marketplace-5"
