#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/../.."
cd "$REPO_ROOT"

# 1. Query the local LoadBalancer IP of the Ingress Controller and write to endpoint.txt
echo "🔍 Resolving local Ingress Controller IP..."
kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='http://{.status.loadBalancer.ingress[0].ip}' > "load-tests/endpoint.txt"

# 2. Print resolved address for verification
RESOLVED_ENDPOINT=$(cat "load-tests/endpoint.txt")
echo "🎯 Resolved Ingress URL: ${RESOLVED_ENDPOINT}"

# 3. Read profile from script parameter (defaults to smoke)
PROFILE="${1:-smoke}"

# 4. Run the k6 script
echo "🚀 Starting k6 load test with profile: ${PROFILE}..."
k6 run -e PROFILE="${PROFILE}" "load-tests/k6-payment-flow.js"
