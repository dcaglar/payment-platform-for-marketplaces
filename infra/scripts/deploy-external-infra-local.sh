#!/usr/bin/env bash
set -euo pipefail

trap 'echo "❌ External infra deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

usage() {
  echo "Usage: $0 <RELEASE_NAME used in helm upgrade install RELEASE_NAME>"
  echo "Example: $0 keycloak/redis/kafka"
  echo "Example: $0 ingress-controller local ingress-nginx"
  exit 1
}

RELEASE_NAME=${1:-}

if [ -z "$RELEASE_NAME" ] then
  usage
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# Set base values file if it exists
    REPO_NAME="bitnami"
    REPO_URL="https://charts.bitnami.com/bitnami"
    CHART="bitnami/keycloak"
    RELEASE_NAME="keycloak"
    NAMESPACE="payment"
    EXTRA_ARGS="--version 20.0.0 --set global.imageRegistry=docker.io --set image.registry=docker.io --set image.repository=bitnamilegacy/keycloak --set image.tag=23.0.7 --set postgresql.enabled=true --set postgresql.image.registry=docker.io --set postgresql.image.repository=bitnamilegacy/postgresql --set postgresql.image.tag=16.4.0-debian-12-r0"
    VALUES_FILE="$REPO_ROOT/infra/helm-values/${RELEASE_NAME}-values-local.yaml"

HELM_ARGS=""
if [ ! -f "$VALUES_FILE" ]; then
  echo "⚠️  No specific values file found at $VALUES_FILE. Proceeding with chart defaults."
  VALUES_FILE=""
else
  HELM_ARGS="-f $VALUES_FILE"
fi

helm repo add "$REPO_NAME" "$REPO_URL"
echo "helm repo add $REPO_NAME $REPO_URL"

#KEYCLOAK
# Note: we are passing HELM_ARGS and EXTRA_ARGS unquoted intentionally so they expand
"helm upgrade --install "$RELEASE_NAME" "$CHART" \
  -n "$NAMESPACE" --create-namespace \
  $HELM_ARGS \
  $EXTRA_ARGS"

echo "helm upgrade --install "$RELEASE_NAME" $CHART \
       -n $NAMESPACE --create-namespace \
       $HELM_ARGS \
       $EXTRA_ARGS"


echo "🚀 Preparing external deployment: $RELEASE_NAME for local environment..."

case "$RELEASE_NAME" in
  keycloak)
    REPO_NAME="bitnami"
    REPO_URL="https://charts.bitnami.com/bitnami"
    CHART="bitnami/keycloak"
    NAMESPACE="payment"
    EXTRA_ARGS="--version 20.0.0 --set global.imageRegistry=docker.io --set image.registry=docker.io --set image.repository=bitnamilegacy/keycloak --set image.tag=23.0.7 --set postgresql.enabled=true --set postgresql.image.registry=docker.io --set postgresql.image.repository=bitnamilegacy/postgresql --set postgresql.image.tag=16.4.0-debian-12-r0"
    ;;

  kafka)
    REPO_NAME="bitnami"
    REPO_URL="https://charts.bitnami.com/bitnami"
    CHART="bitnami/kafka"
    NAMESPACE="payment"
    EXTRA_ARGS="--version 32.3.14"
    ;;

  prometheus-kafka-exporter)
    REPO_NAME="prometheus-community"
    REPO_URL="https://prometheus-community.github.io/helm-charts"
    CHART="prometheus-community/prometheus-kafka-exporter"
    NAMESPACE="payment"
    ;;

  prometheus-postgres-exporter)
    REPO_NAME="prometheus-community"
    REPO_URL="https://prometheus-community.github.io/helm-charts"
    CHART="prometheus-community/prometheus-postgres-exporter"
    NAMESPACE="payment"
    ;;

  redis)
    REPO_NAME="bitnami"
    REPO_URL="https://charts.bitnami.com/bitnami"
    CHART="bitnami/redis"
    NAMESPACE="payment"
    ;;

  keda)
    REPO_NAME="kedacore"
    REPO_URL="https://kedacore.github.io/charts"
    CHART="kedacore/keda"
    NAMESPACE="keda"
    if [[ "$ENV" == "azure" ]]; then
      EXTRA_ARGS="--set nodeSelector.pool=central"
    fi
    ;;

  ingress-nginx)
    REPO_NAME="ingress-nginx"
    REPO_URL="https://kubernetes.github.io/ingress-nginx"
    CHART="ingress-nginx/ingress-nginx"
    NAMESPACE="ingress-controller"
    ;;

  *)
    echo "❌ Unknown external component: $RELEASE_NAME"
    exit 1
    ;;
esac


#Redis
 REPO_NAME="bitnami"
 REPO_URL="https://charts.bitnami.com/bitnami"
 CHART="bitnami/redis"
 NAMESPACE="payment"


echo "✅ Deployment request of $RELEASE_NAME to $ENV helm complete."
