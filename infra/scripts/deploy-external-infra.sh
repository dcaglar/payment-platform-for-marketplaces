#!/bin/bash
# Usage: deploy-external-infra.sh <service-name> <environment>
# Example: ./deploy-external-infra.sh keycloak local
# Example: ./deploy-external-infra.sh kafka azure

set -euo pipefail

trap 'echo "❌ External infra deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR
usage() {
  echo "Usage: $0 <service-name> <environment>"
  echo "Example: $0 keycloak local"
  echo "Example: $0 redis azure"
  echo "Example: $0 ingres-controller azure"
  exit 1
}

SERVICE_NAME=${1:-}
ENV=${2:-LOCAL}

if [ -z "$SERVICE_NAME" ] || [ -z "$ENV" ]; then
  usage
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# Set base values file if it exists
VALUES_FILE="$REPO_ROOT/infra/helm-values/${SERVICE_NAME}-values-${ENV}.yaml"

HELM_ARGS=""
if [ ! -f "$VALUES_FILE" ]; then
  echo "⚠️  No specific values file found at $VALUES_FILE. Proceeding with chart defaults."
  VALUES_FILE=""
else
  HELM_ARGS="-f $VALUES_FILE"
fi

NAMESPACE="payment"
CHART=""
RELEASE_NAME="$SERVICE_NAME"
EXTRA_ARGS=""

echo "🚀 Preparing external deployment: $SERVICE_NAME for $ENV environment..."

case "$SERVICE_NAME" in
  keycloak)
    helm repo add bitnami https://charts.bitnami.com/bitnami
    CHART="bitnami/keycloak"
    NAMESPACE="payment"
    EXTRA_ARGS="--version 20.0.0 --set global.imageRegistry=docker.io --set image.registry=docker.io --set image.repository=bitnamilegacy/keycloak --set image.tag=23.0.7 --set postgresql.enabled=true --set postgresql.image.registry=docker.io --set postgresql.image.repository=bitnamilegacy/postgresql --set postgresql.image.tag=16.4.0-debian-12-r0"
    ;;

  kafka)
    helm repo add bitnami https://charts.bitnami.com/bitnami
    CHART="bitnami/kafka"
    EXTRA_ARGS="--version 32.3.14"
        NAMESPACE="payment";;
  kafka-exporter)
    helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
    CHART="prometheus-community/prometheus-kafka-exporter"
    NAMESPACE="payment"
    ;;
  postgresql-exporter)
    helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
    CHART="prometheus-community/prometheus-postgres-exporter"
    NAMESPACE="payment"
    ;;
  redis)
    helm repo add bitnami https://charts.bitnami.com/bitnami
    CHART="bitnami/redis"
    NAMESPACE="payment"
    ;;
  keda)
    helm repo add kedacore https://kedacore.github.io/charts
    CHART="kedacore/keda"
    NAMESPACE="keda"
    if [[ "$ENV" == "azure" ]]; then
      EXTRA_ARGS="--set nodeSelector.pool=central"
    fi
    ;;
  ingress-controller)
    helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
    CHART="ingress-nginx/ingress-nginx"
    RELEASE_NAME="ingress-nginx"
    NAMESPACE="ingress-nginx"

    ;;
  *)
    echo "❌ Unknown external service: $SERVICE_NAME"
    exit 1
    ;;
esac

helm repo update

echo "📦 Deploying $RELEASE_NAME into namespace $NAMESPACE..."
# Note: we are passing HELM_ARGS and EXTRA_ARGS unquoted intentionally so they expand
# Plain helm — no SOPS-encrypted secrets are used for external infra (keycloak, kafka, redis, keda, ingress-controller)
helm upgrade --install "$RELEASE_NAME" "$CHART" \
  -n "$NAMESPACE" --create-namespace \
  $HELM_ARGS \
  $EXTRA_ARGS


echo "✅ Deployment request  of $SERVICE_NAME to $ENV helm  complete."
