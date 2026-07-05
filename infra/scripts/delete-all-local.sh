#!/usr/bin/env bash
set -euo pipefail

trap 'echo "❌ Error occurred on line $LINENO. Command: $BASH_COMMAND"' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

echo "🛡️  Checking and setting Kubernetes context..."
kubectl config set-context orbstack
kubectl config use-context orbstack
CURRENT_CONTEXT=$(kubectl config current-context || echo "none")

if [[ "$CURRENT_CONTEXT" != "orbstack" ]]; then
  echo "❌ Current context is '$CURRENT_CONTEXT'. Refusing to execute local deletion on the wrong cluster!"
  echo "Run: first kubectl config set-context orbstack , then kubectl config use-context orbstack, and re-run the script again"
  exit 1
fi
echo "ℹ️  Context successfully validated as: $CURRENT_CONTEXT"

echo "🧹 Starting explicit uninstall of all local resources..."

# -----------------------------------------------------------------------------
# 1. PLATFORM SERVICES (LOCAL CHARTS)
#    Deleted in the exact reverse order of their deployment
#    Format: RELEASE_NAME|NAMESPACE
# -----------------------------------------------------------------------------
LOCAL_RELEASES=(
  "payment-central-relay|payment"
  "payment-consumers|payment"
  "central-db|payment"
  "payment-edge-workers|payment"
  "payment-edge-cell|payment"
)

echo "========================================================"
echo "🧹 Phase 1: Uninstalling Platform Services (Local Charts)"
echo "========================================================"

for RELEASE_INFO in "${LOCAL_RELEASES[@]}"; do
  IFS='|' read -r RELEASE_NAME NAMESPACE <<< "$RELEASE_INFO"

  echo "🗑️  Deleting local release $RELEASE_NAME in namespace $NAMESPACE..."
  helm uninstall "$RELEASE_NAME" \
    -n "$NAMESPACE" \
    --wait \
    --timeout=60s \
    --ignore-not-found
    
  # Clean up any lingering local subcharts downloaded by helm dependency update
  CHART_ROOT="$REPO_ROOT/charts/$RELEASE_NAME"
  if [ -d "$CHART_ROOT" ]; then
    echo "🧹 Cleaning up local subcharts for $RELEASE_NAME..."
    rm -rf "$CHART_ROOT/charts"
    rm -f "$CHART_ROOT/Chart.lock"
  fi
done

# -----------------------------------------------------------------------------
# 2. EXTERNAL INFRASTRUCTURE
#    Deleted in the exact reverse order of their deployment
#    Format: RELEASE_NAME|NAMESPACE|REPO_NAME
# -----------------------------------------------------------------------------
EXTERNAL_RELEASES=(
  "nginx-ingress-controller|ingress-controller|ingress-nginx"
  "keda|keda|kedacore"
  "redis|payment|bitnami"
  "postgresql-exporter|payment|prometheus-community"
  "kafka-exporter|payment|prometheus-community"
  "kafka|payment|bitnami"
  "keycloak|payment|bitnami"
)

echo "========================================================"
echo "🧹 Phase 2: Uninstalling External Infrastructure"
echo "========================================================"

for RELEASE_INFO in "${EXTERNAL_RELEASES[@]}"; do
  IFS='|' read -r RELEASE_NAME NAMESPACE REPO_NAME <<< "$RELEASE_INFO"

  echo "🗑️  Deleting external release $RELEASE_NAME in namespace $NAMESPACE..."
  helm uninstall "$RELEASE_NAME" \
    -n "$NAMESPACE" \
    --wait \
    --timeout=60s \
    --ignore-not-found
done

# Clean up Helm Repositories
remove_helm_repo() {
  local repo=$1
  local err
  err=$(helm repo remove "$repo" 2>&1) && return 0

  if echo "$err" | grep -q "no repo named"; then
    echo "   ${repo} repo already removed."
  else
    echo "❌ Failed to remove Helm repository ${repo}: ${err}" >&2
    exit 1
  fi
}

echo "🧹 Phase 3: Removing Helm repositories..."
for RELEASE_INFO in "${EXTERNAL_RELEASES[@]}"; do
  IFS='|' read -r RELEASE_NAME NAMESPACE REPO_NAME <<< "$RELEASE_INFO"
  
  if [ -n "$REPO_NAME" ]; then
    remove_helm_repo "$REPO_NAME"
  fi
done

# -----------------------------------------------------------------------------
# 3. ULTIMATE NUCLEAR RESET
# -----------------------------------------------------------------------------
if command -v orb &>/dev/null; then
  echo "========================================================"
  echo "🔥 OrbStack CLI detected. Obliterating and recreating the local Kubernetes cluster for a perfectly clean slate..."
  orb delete k8s  || y
  orb start k8s
  echo "✅ Entire local Kubernetes cluster obliterated and rebuilt successfully."
fi

echo "✅ All uninstalls completed successfully!"
