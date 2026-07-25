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

# --- Ensure OrbStack Kubernetes is enabled and reachable (required for the deploy) ---
# k8s.enable defaults to false; without it the helm steps fail deep in step 2 with a
# cryptic "Kubernetes cluster unreachable". Enable it here so local deploy is self-sufficient.
echo "🛡️  Ensuring OrbStack Kubernetes is enabled..."
orb start >/dev/null 2>&1 || true
if [[ "$(orb config get k8s.enable 2>/dev/null)" != "true" ]]; then
  echo "🔧 OrbStack Kubernetes was disabled — enabling it (orb config set k8s.enable true)..."
  orb config set k8s.enable true
  echo "♻️  Restarting OrbStack to apply Kubernetes..."
  orb stop  >/dev/null 2>&1 || true
  orb start >/dev/null 2>&1 || true
fi

echo "⏳ Waiting for the Kubernetes API to be reachable..."
for i in $(seq 1 45); do
  if kubectl cluster-info >/dev/null 2>&1; then
    echo "✅ Kubernetes cluster is reachable."
    break
  fi
  if [[ "$i" -eq 45 ]]; then
    echo "❌ Kubernetes cluster still unreachable after ~3 min."
    echo "   Check: 'orb status', 'orb config get k8s.enable', 'kubectl get nodes'."
    exit 1
  fi
  sleep 4
done


SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/../.."
cd "$REPO_ROOT"

echo "DIR is $REPO_ROOT $SCRIPT_DIR"
echo "🚀 Starting complete local deployment of the Payment Platform..."

echo "📦 Step 1/3: Building all Docker images..."
build-all-payment-platform-images-and-push.sh

echo "🔧 Step 2/3: Deploying external infrastructure (Redis, Keycloak, Kafka)..."
deploy-all-external-infra-local.sh

echo "⚙️ Step 3/3: Deploying payment platform services to local..."
deploy-payment-platform-services-local.sh

echo "✅ All deployment manifests successfully submitted to local Kubernetes."
echo "   Kubernetes is now natively resolving dependencies via initContainers."
echo "   You can track progress via: kubectl get pods -n payment -w"
