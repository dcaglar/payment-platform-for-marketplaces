#!/usr/bin/env bash
set -euo pipefail

trap 'echo "❌ Local infrastructure deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/../.."
cd "$REPO_ROOT"

echo "🛡️$REPO_ROOT  Checking and setting kubectl context to orbstack..."
kubectl config set-context orbstack
kubectl config use-context orbstack
CURRENT_CONTEXT=$(kubectl config current-context || echo "none")

if [[ "$CURRENT_CONTEXT" != "orbstack" ]]; then
  echo "❌ Current context is '$CURRENT_CONTEXT'. Refusing to deploy to the wrong cluster!"
  exit 1
fi

echo "🚀 Deploying platform services locally..."

echo "========================================================"
echo "📦 1. Deploying central-db"
helm dependency update "$REPO_ROOT/charts/central-db"
helm secrets upgrade --install central-db "$REPO_ROOT/charts/central-db" \
  -n payment --create-namespace \
  -f "$REPO_ROOT/charts/central-db/values.yaml" \
    -f "$REPO_ROOT/charts/central-db/local/values.yaml" \
  -f "secrets://$REPO_ROOT/central-db-sops-secrets.yaml"

echo "========================================================"
echo "📦 2. Deploying payment-edge-cell"
helm dependency update "$REPO_ROOT/charts/payment-edge-cell"
helm secrets upgrade --install payment-edge-cell "$REPO_ROOT/charts/payment-edge-cell" \
  -n payment --create-namespace \
  -f "$REPO_ROOT/charts/payment-edge-cell/values.yaml" \
  -f "$REPO_ROOT/charts/payment-edge-cell/local/values.yaml" \
  -f "secrets://$REPO_ROOT/edge-cell-sops-secrets.yaml"

echo "========================================================"
echo "📦 3. Deploying payment-edge-workers"
helm dependency update "$REPO_ROOT/charts/payment-edge-workers"
helm secrets upgrade --install payment-edge-workers "$REPO_ROOT/charts/payment-edge-workers" \
  -n payment --create-namespace \
  -f "$REPO_ROOT/charts/payment-edge-workers/values.yaml" \
    -f "$REPO_ROOT/charts/payment-edge-workers/local/values.yaml" \
  -f "secrets://$REPO_ROOT/edge-cell-sops-secrets.yaml" \
  -f "secrets://$REPO_ROOT/central-db-sops-secrets.yaml"

echo "========================================================"
echo "📦 4. Deploying payment-central-relay"
helm dependency update "$REPO_ROOT/charts/payment-central-relay"
helm secrets upgrade --install payment-central-relay "$REPO_ROOT/charts/payment-central-relay" \
  -n payment --create-namespace \
  -f "$REPO_ROOT/charts/payment-central-relay/values.yaml" \
    -f "$REPO_ROOT/charts/payment-central-relay/local/values.yaml" \
  -f "secrets://$REPO_ROOT/central-db-sops-secrets.yaml"

echo "========================================================"
echo "📦 5. Deploying payment-consumers"
helm dependency update "$REPO_ROOT/charts/payment-consumers"
helm secrets upgrade --install payment-consumers "$REPO_ROOT/charts/payment-consumers" \
  -n payment --create-namespace \
  -f "$REPO_ROOT/charts/payment-consumers/values.yaml" \
    -f "$REPO_ROOT/charts/payment-consumers/local/values.yaml" \
  -f "secrets://$REPO_ROOT/central-db-sops-secrets.yaml"


  echo "📦 5. Deploying open telemetry trace/metric collector "
  OTEL_VALUES_FILE="$REPO_ROOT/infra/helm-values/opentelemetry-collector-values-local.yaml"
  helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts
  helm repo update open-telemetry
  helm  upgrade --install my-opentelemetry-collector open-telemetry/opentelemetry-collector \
    -n payment \
    -f "$OTEL_VALUES_FILE"

echo "========================================================"
echo "✅ All local platform services deployed sequentially."
echo "Check progress via: kubectl get pods -A"
