#!/usr/bin/env bash
set -euo pipefail

# Fail early, print error, and terminate
trap 'echo "❌ Error occurred on line $LINENO. Command: $BASH_COMMAND"' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

echo "🛡️  Checking and setting Kubernetes context..."
kubectl config set-context orbstack
kubectl config use-context orbstack
CURRENT_CONTEXT=$(kubectl config current-context || echo "none")

if [[ "$CURRENT_CONTEXT" != "orbstack" ]]; then
  echo "❌ Current context is '$CURRENT_CONTEXT'. Refusing to deploy to the wrong cluster!"
  exit 1
fi

VALUES_FILE="$REPO_ROOT/infra/helm-values/monitoring-stack-values-local.yaml"

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

# Execute helm upgrade --install and gracefully catch "already exists" edge cases
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
  -f "$REPO_ROOT/infra/helm-values/tempo-values-local.yaml"

echo "========================================================"
# Apps push metrics via OpenTelemetry; their ServiceMonitors are off in their charts' values.
# Only ingress-nginx is scraped by Prometheus (NGINX can't push OTel), so enable its ServiceMonitor
# in the running release now that the Prometheus Operator CRDs exist.
echo "🔌 Enabling Prometheus scraping for ingress-nginx..."

# Only "release not found" means "not installed yet"; any other error (cluster unreachable, auth, ...) must fail loudly.
if status_out=$(helm status ingress-nginx -n ingress-controller 2>&1); then
  helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx >/dev/null 2>&1 || true
  if ! repo_out=$(helm repo update ingress-nginx 2>&1); then
    log_error "ingress-nginx (helm repo update)" "$repo_out"
    exit 1
  fi

  if ! upgrade_out=$(helm upgrade ingress-nginx ingress-nginx/ingress-nginx -n ingress-controller \
      --reuse-values --set controller.metrics.serviceMonitor.enabled=true 2>&1); then
    log_error "ingress-nginx (enable ServiceMonitor)" "$upgrade_out"
    exit 1
  fi

  # Verify the ServiceMonitor really exists; don't trust the upgrade alone.
  if ! sm_out=$(kubectl get servicemonitor -n ingress-controller -l app.kubernetes.io/name=ingress-nginx -o name 2>&1) || [[ -z "$sm_out" ]]; then
    log_error "ingress-nginx ServiceMonitor" "upgrade succeeded but no ServiceMonitor found in namespace ingress-controller. ${sm_out}"
    exit 1
  fi
  log_success "ingress-nginx ServiceMonitor (${sm_out})"
elif echo "$status_out" | grep -qi "release: not found"; then
  echo "ℹ️  ingress-nginx is not installed yet. After deploy-all-external-infra-local.sh, re-run this script to enable its ServiceMonitor."
else
  log_error "ingress-nginx (helm status)" "$status_out"
  exit 1
fi
