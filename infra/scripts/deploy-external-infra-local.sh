#!/usr/bin/env bash
set -euo pipefail

trap 'echo "❌ External infra deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

usage() {
  echo "Usage: $0 <component>"
  echo "Components: keycloak | kafka | redis | keda | ingress-nginx | prometheus-kafka-exporter | prometheus-postgres-exporter"
  echo "Example: $0 prometheus-kafka-exporter"
  exit 1
}

RELEASE_NAME=${1:-}
ENV="local"

if [ -z "$RELEASE_NAME" ]; then
  usage
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

echo "🚀 Preparing external deployment: $RELEASE_NAME for $ENV environment..."

EXTRA_ARGS=""
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
    ;;

  ingress-nginx)
    REPO_NAME="ingress-nginx"
    REPO_URL="https://kubernetes.github.io/ingress-nginx"
    CHART="ingress-nginx/ingress-nginx"
    NAMESPACE="ingress-controller"
    ;;

  *)
    echo "❌ Unknown external component: $RELEASE_NAME"
    usage
    ;;
esac

# Use infra/helm-values/<component>-values-local.yaml when it exists
VALUES_FILE="$REPO_ROOT/infra/helm-values/${RELEASE_NAME}-values-${ENV}.yaml"
HELM_ARGS=""
if [ -f "$VALUES_FILE" ]; then
  HELM_ARGS="-f $VALUES_FILE"
else
  echo "⚠️  No values file at $VALUES_FILE. Using chart defaults."
fi

helm repo add "$REPO_NAME" "$REPO_URL" --force-update
helm repo update "$REPO_NAME"

# HELM_ARGS and EXTRA_ARGS are unquoted on purpose so they split into separate arguments
echo "helm upgrade --install $RELEASE_NAME $CHART -n $NAMESPACE --create-namespace $HELM_ARGS $EXTRA_ARGS"
helm upgrade --install "$RELEASE_NAME" "$CHART" \
  -n "$NAMESPACE" --create-namespace \
  $HELM_ARGS \
  $EXTRA_ARGS

echo "✅ Deployment request of $RELEASE_NAME to $ENV helm complete."
