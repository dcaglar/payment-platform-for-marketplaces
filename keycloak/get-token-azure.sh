#!/usr/bin/env bash
set -euo pipefail

#KEYCLOAK_IP=$(kubectl get svc -n payment keycloak -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
export KEYCLOAK_IP=98.64.91.238
KEYCLOAK_URL="http://${KEYCLOAK_IP}:8080"
bash "$(dirname "$0")/get-token.sh" "$KEYCLOAK_URL" "$@"
