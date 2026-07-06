#!/usr/bin/env bash
set -euo pipefail



trap 'echo "❌ Deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

# Safely create or update the context definition locally
kubectl config set-context orbstack \
  --cluster=orbstack \
  --user=orbstack \
  --namespace=payment >/dev/null

# Switch active target to it
kubectl config use-context orbstack >/dev/null

CURRENT_CONTEXT=$(kubectl config current-context || echo "none")

if [[ "$CURRENT_CONTEXT" != "orbstack" ]]; then
  log_error "Current context is '$CURRENT_CONTEXT'. Refusing to execute full local deployment to the wrong cluster!"
  echo -e "Run: ${YELLOW}kubectl config set-context orbstack${RESET} and try again."
  exit 1
fi


SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/../.."
cd "$REPO_ROOT"

echo "DIR is $REPO_ROOT $SCRIPT_DIR"
echo "🚀 Starting complete local deployment of the Payment Platform..."

echo "📦 Step 1/3: Building all Docker images..."
"$SCRIPT_DIR/build-all-payment-platform-images-and-push.sh"

echo "🔧 Step 2/3: Deploying external infrastructure (Redis, Keycloak, Kafka)..."
"$SCRIPT_DIR/deploy-all-external-infra-local.sh"

echo "⚙️ Step 3/3: Deploying payment platform services to local..."
"$SCRIPT_DIR/deploy-payment-platform-services-local.sh"

echo "✅ All deployment manifests successfully submitted to local Kubernetes."
echo "   Kubernetes is now natively resolving dependencies via initContainers."
echo "   You can track progress via: kubectl get pods -n payment -w"
