#!/usr/bin/env bash
# Deploys all payment platform services to Local Kubernetes (OrbStack)
#
# Usage: ./deploy-payment-platform-services-local.sh
set -euo pipefail

trap 'echo "❌ Local payment platform services deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

echo "🛡️  Checking and setting Kubernetes context..."
kubectl config set-context orbstack
kubectl config use-context orbstack
CURRENT_CONTEXT=$(kubectl config current-context || echo "none")

if [[ "$CURRENT_CONTEXT" != "orbstack" ]]; then
  echo "❌ Current context is '$CURRENT_CONTEXT'. Refusing to deploy payment platform services to the wrong cluster!"
  echo "Run: first kubectl config set-context orbstack , then kubectl config use-context orbstack, and re-run the script again"
  exit 1
fi
echo "ℹ️  Deploying to context: $CURRENT_CONTEXT"

echo "🚀 Deploying all payment platform services to Local in a serialized manner..."

# Format: RELEASE_NAME|NAMESPACE|SECRET_ARGS
RELEASES=(
  "payment-edge-cell|payment|-f secrets://$REPO_ROOT/edge-cell-sops-secrets.yaml"
  "payment-edge-workers|payment|-f secrets://$REPO_ROOT/edge-cell-sops-secrets.yaml -f secrets://$REPO_ROOT/central-db-sops-secrets.yaml"
  "central-db|payment|-f secrets://$REPO_ROOT/central-db-sops-secrets.yaml"
  "payment-consumers|payment|-f secrets://$REPO_ROOT/central-db-sops-secrets.yaml"
  "payment-central-relay|payment|-f secrets://$REPO_ROOT/central-db-sops-secrets.yaml"
)

log_success() {
  echo "✅ SUCCESS: Manifests for '$1' successfully accepted by Kubernetes API."
}

log_error() {
  echo "❌ ERROR: Failed to submit manifests for '$1'."
  echo "Details: $2"
}

for RELEASE_INFO in "${RELEASES[@]}"; do
  IFS='|' read -r RELEASE_NAME NAMESPACE SECRET_ARGS <<< "$RELEASE_INFO"

  if [ "$SECRET_ARGS" == "null" ]; then SECRET_ARGS=""; fi

  echo "========================================================"
  echo "📦 Preparing deployment for: $RELEASE_NAME"

  CHART_ROOT="$REPO_ROOT/charts/$RELEASE_NAME"
  if [ ! -d "$CHART_ROOT" ]; then
    echo "❌ Error: Chart directory $CHART_ROOT does not exist."
    exit 1
  fi

  VALUES_ARGS="-f $CHART_ROOT/values.yaml -f $CHART_ROOT/local/values.yaml"

  HELM_CMD="helm upgrade"
  if [[ -n "$SECRET_ARGS" ]]; then
    HELM_CMD="helm secrets upgrade"
    echo "🔐 Using helm-secrets plugin with SOPS decryption."
  fi

  echo "⬇️  Updating helm dependencies for $RELEASE_NAME..."
  helm dependency update "$CHART_ROOT"

  echo "🚀 Executing $HELM_CMD --install for $RELEASE_NAME in namespace $NAMESPACE"
  
  # Execute helm upgrade --install and gracefully catch "already exists" edge cases
  if ! err=$($HELM_CMD --install "$RELEASE_NAME" "$CHART_ROOT" \
    -n "$NAMESPACE" --create-namespace \
    $VALUES_ARGS \
    $SECRET_ARGS 2>&1); then
    
    if echo "$err" | grep -qi "already exists"; then
      echo "⚠️  WARNING: Release $RELEASE_NAME already exists or encountered a non-fatal collision. Continuing..."
    else
      log_error "$RELEASE_NAME" "$err"
      exit 1
    fi
  else
    log_success "$RELEASE_NAME"
  fi

  echo "🧹 Cleaning up downloaded .tgz dependencies..."
  rm -rf "$CHART_ROOT/charts"
  rm -f "$CHART_ROOT/Chart.lock"

  echo "🔄 Forcing pod restart to pull the latest image..."
  kubectl rollout restart deployment "$RELEASE_NAME" -n "$NAMESPACE" 2>/dev/null || true
  kubectl rollout restart statefulset "$RELEASE_NAME" -n "$NAMESPACE" 2>/dev/null || true
done

echo "========================================================"
echo "✅ All manifests successfully submitted to Local Kubernetes via helm."
echo "Kubernetes is now resolving dependencies natively via initContainers."
echo "Check progress via: kubectl get pods -n payment -w"
