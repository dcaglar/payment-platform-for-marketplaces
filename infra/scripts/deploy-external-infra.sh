#!/usr/bin/env bash
set -euo pipefail

trap 'echo "❌ External infra deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

usage() {
  echo "Usage: $0 <infra-name> <environment> [namespace]"
  echo "Example: $0 keycloak local"
  echo "Example: $0 redis azure"
  echo "Example: $0 ingress-controller local ingress-nginx"
  exit 1
}

INFRA_NAME=${1:-}
ENV=${2:-LOCAL}
OVERRIDE_NS=${3:-}

if [ -z "$INFRA_NAME" ] || [ -z "$ENV" ]; then
  usage
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# Set base values file if it exists
VALUES_FILE="$REPO_ROOT/infra/helm-values/${INFRA_NAME}-values-${ENV}.yaml"

HELM_ARGS=""
if [ ! -f "$VALUES_FILE" ]; then
  echo "⚠️  No specific values file found at $VALUES_FILE. Proceeding with chart defaults."
  VALUES_FILE=""
else
  HELM_ARGS="-f $VALUES_FILE"
fi

REPO_NAME=""
REPO_URL=""
CHART=""
RELEASE_NAME=""
NAMESPACE="payment"
EXTRA_ARGS=""

echo "🚀 Preparing external deployment: $INFRA_NAME for $ENV environment..."

case "$INFRA_NAME" in
  keycloak)
    REPO_NAME="bitnami"
    REPO_URL="https://charts.bitnami.com/bitnami"
    CHART="bitnami/keycloak"
    RELEASE_NAME="keycloak"
    NAMESPACE="payment"
    EXTRA_ARGS="--version 20.0.0 --set global.imageRegistry=docker.io --set image.registry=docker.io --set image.repository=bitnamilegacy/keycloak --set image.tag=23.0.7 --set postgresql.enabled=true --set postgresql.image.registry=docker.io --set postgresql.image.repository=bitnamilegacy/postgresql --set postgresql.image.tag=16.4.0-debian-12-r0"
    ;;

  kafka)
    REPO_NAME="bitnami"
    REPO_URL="https://charts.bitnami.com/bitnami"
    CHART="bitnami/kafka"
    RELEASE_NAME="kafka"
    NAMESPACE="payment"
    EXTRA_ARGS="--version 32.3.14"
    ;;

  kafka-exporter)
    REPO_NAME="prometheus-community"
    REPO_URL="https://prometheus-community.github.io/helm-charts"
    CHART="prometheus-community/prometheus-kafka-exporter"
    RELEASE_NAME="prometheus-kafka-exporter"
    NAMESPACE="payment"
    ;;

  postgresql-exporter)
    REPO_NAME="prometheus-community"
    REPO_URL="https://prometheus-community.github.io/helm-charts"
    CHART="prometheus-community/prometheus-postgres-exporter"
    RELEASE_NAME="prometheus-postgres-exporter"
    NAMESPACE="payment"
    ;;

  redis)
    REPO_NAME="bitnami"
    REPO_URL="https://charts.bitnami.com/bitnami"
    CHART="bitnami/redis"
    RELEASE_NAME="redis"
    NAMESPACE="payment"
    ;;

  keda)
    REPO_NAME="kedacore"
    REPO_URL="https://kedacore.github.io/charts"
    CHART="kedacore/keda"
    RELEASE_NAME="keda"
    NAMESPACE="keda"
    if [[ "$ENV" == "azure" ]]; then
      EXTRA_ARGS="--set nodeSelector.pool=central"
    fi
    ;;

  nginx-ingress-controller)
    REPO_NAME="ingress-nginx"
    REPO_URL="https://kubernetes.github.io/ingress-nginx"
    CHART="ingress-nginx/ingress-nginx"
    RELEASE_NAME="ingress-nginx"
    NAMESPACE="ingress-controller"
    ;;

  *)
    echo "❌ Unknown external component: $INFRA_NAME"
    exit 1
    ;;
esac

# Allow command line namespace override if passed as 3rd argument
NAMESPACE=${OVERRIDE_NS:-$NAMESPACE}

# Register the Helm repository dynamically using the configured variables
helm repo add "$REPO_NAME" "$REPO_URL"
helm repo update

echo "📦 Deploying $RELEASE_NAME into namespace $NAMESPACE..."
# Note: we are passing HELM_ARGS and EXTRA_ARGS unquoted intentionally so they expand
helm upgrade --install "$RELEASE_NAME" "$CHART" \
  -n "$NAMESPACE" --create-namespace \
  $HELM_ARGS \
  $EXTRA_ARGS

echo "✅ Deployment request of $INFRA_NAME to $ENV helm complete."
