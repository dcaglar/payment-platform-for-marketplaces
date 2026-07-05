#!/usr/bin/env bash
set -euo pipefail

trap 'echo "❌ Deployment script failed on line $LINENO. Command: $BASH_COMMAND"' ERR

usage() {
  echo "Usage: $0 <service-name> <environment>"
  echo "Example: $0 payment-central-relay local"
  echo "Example: $0 payment-edge-cell azure"
  exit 1
}

SERVICE_NAME=${1:-}
ENV=${2:-}
NS=${3:-"payment"}

if [ -z "$SERVICE_NAME" ] || [ -z "$ENV" ]; then
  usage
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CHART_ROOT="$REPO_ROOT/charts/$SERVICE_NAME"

if [ ! -d "$CHART_ROOT" ]; then
  echo "❌ Error: Chart directory $CHART_ROOT does not exist."
  exit 1
fi

# 1. Determine secrets and values files based on environment
SECRET_ARGS=""
VALUES_ARGS=""

if [[ "$SERVICE_NAME" == "payment-central-relay" ]]; then
  SECRET_ARGS="-f secrets://$REPO_ROOT/central-db-sops-secrets.yaml"
elif [[ "$SERVICE_NAME" == "payment-edge-cell" ]]; then
  SECRET_ARGS="-f secrets://$REPO_ROOT/edge-cell-sops-secrets.yaml"
elif [[ "$SERVICE_NAME" == "payment-edge-workers" ]]; then
  SECRET_ARGS="-f secrets://$REPO_ROOT/edge-cell-sops-secrets.yaml -f secrets://$REPO_ROOT/central-db-sops-secrets.yaml"
elif [[ "$SERVICE_NAME" == "central-db" ]]; then
  SECRET_ARGS="-f secrets://$REPO_ROOT/central-db-sops-secrets.yaml"
elif [[ "$SERVICE_NAME" == "payment-consumers" ]]; then
  SECRET_ARGS="-f secrets://$REPO_ROOT/central-db-sops-secrets.yaml"
fi

if [[ "$ENV" == "local" ]]; then
  VALUES_ARGS="-f $CHART_ROOT/values.yaml -f $CHART_ROOT/local/values.yaml"
elif [[ "$ENV" == "azure" ]]; then
  VALUES_ARGS="-f $CHART_ROOT/values.yaml -f $CHART_ROOT/azure/values.yaml"
else
  echo "❌ Unknown environment: $ENV"
  exit 1
fi

HELM_CMD="helm upgrade"
if [[ -n "$SECRET_ARGS" ]]; then
  HELM_CMD="helm secrets upgrade"
fi

echo "🚀 Deploying $SERVICE_NAME to $ENV environment..."

# 2. Update Helm dependencies (downloads .tgz archives into charts/)
echo "📦 Updating helm dependencies..."
helm dependency update "$CHART_ROOT"

# 3. Execute Helm upgrade/install
$HELM_CMD --install "$SERVICE_NAME" "$CHART_ROOT" \
-n "$NS" --create-namespace \
  --wait --atomic --timeout 10m \
  $VALUES_ARGS \
  $SECRET_ARGS

# 4. Clean up downloaded .tgz dependencies to keep workspace clean
rm -rf "$CHART_ROOT/charts"
rm -f "$CHART_ROOT/Chart.lock"

# 5. For local environment, trigger rollout restart to pull latest images
if [[ "$ENV" == "local" ]]; then
  echo "🔄 Forcing pod restart to pull the latest image..."
  kubectl rollout restart deployment "$SERVICE_NAME" -n "$NS" 2>/dev/null || true
  kubectl rollout restart statefulset "$SERVICE_NAME" -n "$NS" 2>/dev/null || true
fi

echo "✅ Deployment of $SERVICE_NAME to $ENV complete."
