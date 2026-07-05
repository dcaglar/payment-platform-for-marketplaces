#!/usr/bin/env bash
set -euo pipefail

# 1. Dynamically retrieve the local LoadBalancer IP of the Ingress Controller
INGRESS_IP=$(kubectl get svc ingress-nginx-controller -n ingress-nginx -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "127.0.0.1")

echo "🎯 Auto-detected local Ingress IP: ${INGRESS_IP}"

# 2. Run k6 with the profile (passed as first arg, defaults to smoke)
PROFILE="${1:-smoke}"

k6 run \
  -e PROFILE="$PROFILE" \
  -e API_BASE_URL="http://${INGRESS_IP}" \
  "$(dirname "$0")/k6-payment-flow.js"
