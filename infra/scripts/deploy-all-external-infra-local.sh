#!/usr/bin/env bash
set -euo pipefail

trap 'echo "❌ Local external infra deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

echo "🛡️  Checking and setting kubectl context to orbstack..."
kubectl config set-context orbstack
kubectl config use-context orbstack
CURRENT_CONTEXT=$(kubectl config current-context || echo "none")

if [[ "$CURRENT_CONTEXT" != "orbstack" ]]; then
  echo "❌ Current context is '$CURRENT_CONTEXT'. Refusing to deploy to the wrong cluster!"
  echo "Run: first kubectl config set-context orbstack , then kubectl config use-context orbstack, and re-run the script again"
  exit 1
fi

echo "🚀 Deploying all external infrastructure locally in a serialized manner..."

# External dependencies (no local chart folder). Values files ALWAYS exist in infra/helm-values/
# Format: RELEASE_NAME|REPO_NAME|REPO_URL|CHART_NAME|NAMESPACE|EXTRA_ARGS
# NOTE: RELEASE_NAME is intentionally aligned with the helm-values file prefix (e.g. nginx-ingress-controller -> nginx-ingress-controller-values-local.yaml)
RELEASES=(
  "keycloak|bitnami|https://charts.bitnami.com/bitnami|bitnami/keycloak|payment|--version 20.0.0 --set global.imageRegistry=docker.io --set image.registry=docker.io --set image.repository=bitnamilegacy/keycloak --set image.tag=23.0.7 --set postgresql.enabled=true --set postgresql.image.registry=docker.io --set postgresql.image.repository=bitnamilegacy/postgresql --set postgresql.image.tag=16.4.0-debian-12-r0"
  "kafka|bitnami|https://charts.bitnami.com/bitnami|bitnami/kafka|payment|--version 32.3.14"
#  "prometheus-kafka-exporter|prometheus-community|https://prometheus-community.github.io/helm-charts|prometheus-community/prometheus-kafka-exporter|payment|null"
#  "prometheus-postgres-exporter|prometheus-community|https://prometheus-community.github.io/helm-charts|prometheus-community/prometheus-postgres-exporter|payment|null"
 "redis|bitnami|https://charts.bitnami.com/bitnami|bitnami/redis|payment|null"
  "keda|kedacore|https://kedacore.github.io/charts|kedacore/keda|keda|null"
  "ingress-nginx|ingress-nginx|https://kubernetes.github.io/ingress-nginx|ingress-nginx/ingress-nginx|ingress-controller|null"
)

ADDED_REPOS=""

log_success() {
  echo "✅ SUCCESS: Manifests for '$1' successfully accepted by Kubernetes API."
}

log_error() {
  echo "❌ ERROR: Failed to submit manifests for '$1'."
  echo "Details: $2"
}

for RELEASE_INFO in "${RELEASES[@]}"; do
  IFS='|' read -r RELEASE_NAME REPO_NAME REPO_URL CHART NAMESPACE EXTRA_ARGS <<< "$RELEASE_INFO"

  echo "========================================================"
  echo "📦 Preparing deployment for: $RELEASE_NAME"

  if [ "$EXTRA_ARGS" == "null" ]; then EXTRA_ARGS=""; fi

  # Dynamically resolve values file based on Release Name convention
  VALUES_FILE="$REPO_ROOT/infra/helm-values/${RELEASE_NAME}-values-local.yaml"
  HELM_ARGS=""
  
  if [ -f "$VALUES_FILE" ]; then
    HELM_ARGS="-f $VALUES_FILE"
    echo "📄 Using values file: $VALUES_FILE"
  else
    echo "⚠️  WARNING: Expected values file $VALUES_FILE does not exist. Proceeding without it (this is normal for charts like Keda)."
  fi

  if [[ ! " $ADDED_REPOS " =~ " $REPO_NAME " ]]; then
    echo "🔄 Adding/updating helm repo: $REPO_NAME ($REPO_URL)"
    helm repo add "$REPO_NAME" "$REPO_URL"
    helm repo update "$REPO_NAME"
    ADDED_REPOS="$ADDED_REPOS $REPO_NAME"
  fi

  echo "🚀 Executing helm upgrade --install for $RELEASE_NAME in namespace $NAMESPACE"
  
  # Execute helm upgrade --install and gracefully catch "already exists" edge cases
  if ! err=$(helm upgrade --install "$RELEASE_NAME" "$CHART" \
    -n "$NAMESPACE" --create-namespace \
    $HELM_ARGS \
    $EXTRA_ARGS 2>&1); then
    
    if echo "$err" | grep -qi "already exists"; then
      echo "⚠️  WARNING: Release $RELEASE_NAME already exists or encountered a non-fatal collision. Continuing..."
    else
      log_error "$RELEASE_NAME" "$err"
      exit 1
    fi
  else
    log_success "$RELEASE_NAME"
  fi

done

echo "========================================================"
echo "✅ All local external infrastructure components deployed sequentially."
echo "Check progress via: kubectl get pods -A"
