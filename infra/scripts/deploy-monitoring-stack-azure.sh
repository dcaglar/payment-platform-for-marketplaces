#!/usr/bin/env bash
set -euo pipefail

trap 'echo "❌ Azure monitoring stack deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

echo "az login is being performed"
az login
echo "active subscription is being set to 7ff93b69-058b-4fee-8dc3-933e9d0d1b86"
az account set --subscription "7ff93b69-058b-4fee-8dc3-933e9d0d1b86"
az aks get-credentials --resource-group rg-payment-platform-loadtest --name aks-payment-loadtest --overwrite-existing

CURRENT_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "none")

if [[ "$CURRENT_CONTEXT" != "aks-payment-loadtest" ]]; then
  echo "❌ Current context is '$CURRENT_CONTEXT'. Refusing to deploy to the wrong cluster!"
  exit 1
fi

VALUES_FILE="$REPO_ROOT/infra/helm-values/monitoring-stack-values-azure.yaml"

echo "========================================================"
echo "▶️  Deploying kube-prometheus-stack"
echo "========================================================"

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update prometheus-community

log_success() {
  echo "✅ SUCCESS: Manifests for '$1' successfully accepted by Kubernetes API."
}

log_error() {
  echo "❌ ERROR: Failed to submit manifests for '$1'."
  echo "Details: $2"
}

if ! err=$(helm upgrade --install prometheus-stack prometheus-community/kube-prometheus-stack \
  -n monitoring --create-namespace \
  -f "$VALUES_FILE" 2>&1); then
  
  if echo "$err" | grep -qi "already exists"; then
    echo "⚠️  WARNING: Object already exists. Continuing..."
  else
    log_error "prometheus-stack" "$err"
    exit 1
  fi
else
  log_success "prometheus-stack"
fi

echo "✅ kube-prometheus-stack successfully deployed to monitoring namespace."
echo "========================================================"
echo "▶️  Deploying Tempo"
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update grafana
helm upgrade --install tempo grafana/tempo \
  -n monitoring --create-namespace \
  -f "$REPO_ROOT/infra/helm-values/tempo-values-azure.yaml"

echo "========================================================"
echo "🚀 Ensuring application ServiceMonitors are DISABLED (We use OpenTelemetry Push Model!)..."
yq -i '.controller.metrics.serviceMonitor.enabled = true' "$REPO_ROOT/infra/helm-values/ingress-nginx-values-azure.yaml" || true
yq -i '.serviceMonitor.enabled = false' "$REPO_ROOT/charts/payment-edge-cell/azure/values.yaml" || true
yq -i '.serviceMonitor.enabled = false' "$REPO_ROOT/charts/payment-consumers/azure/values.yaml" || true
yq -i '.serviceMonitor.enabled = false' "$REPO_ROOT/charts/payment-central-relay/azure/values.yaml" || true
yq -i '.serviceMonitor.enabled = false' "$REPO_ROOT/charts/payment-edge-workers/azure/values.yaml" || true
echo "✅ OpenTelemetry is active! Prometheus scraping (ServiceMonitors) is turned off for applications."
