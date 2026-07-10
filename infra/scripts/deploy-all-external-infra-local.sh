#!/usr/bin/env bash
set -euo pipefail

trap 'echo "❌ Local external infra deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"
echo "DIR is $REPO_ROOT $SCRIPT_DIR"

echo "🛡️  Checking and setting kubectl context to orbstack..."
kubectl config set-context orbstack
kubectl config use-context orbstack
CURRENT_CONTEXT=$(kubectl config current-context || echo "none")

if [[ "$CURRENT_CONTEXT" != "orbstack" ]]; then
  echo "❌ Current context is '$CURRENT_CONTEXT'. Refusing to deploy to the wrong cluster!"
  exit 1
fi

echo "🚀 Deploying all external infrastructure locally..."

echo "========================================================"
echo "📦 1. Deploying Keycloak"
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update bitnami
helm upgrade --install keycloak bitnami/keycloak \
  -n payment --create-namespace \
  -f "$REPO_ROOT/infra/helm-values/keycloak-values-local.yaml" \
  --version 20.0.0 \
  --set global.imageRegistry=docker.io \
  --set image.registry=docker.io \
  --set image.repository=bitnamilegacy/keycloak \
  --set image.tag=23.0.7 \
  --set postgresql.enabled=true \
  --set postgresql.image.registry=docker.io \
  --set postgresql.image.repository=bitnamilegacy/postgresql \
  --set postgresql.image.tag=16.4.0-debian-12-r0

echo "========================================================"
echo "📦 2. Deploying Kafka"
helm upgrade --install kafka bitnami/kafka \
  -n payment --create-namespace \
  -f "$REPO_ROOT/infra/helm-values/kafka-values-local.yaml" \
  --version 32.3.14

echo "========================================================"
echo "📦 3. Deploying Redis"
helm upgrade --install redis bitnami/redis \
  -n payment --create-namespace \
  -f "$REPO_ROOT/infra/helm-values/redis-values-local.yaml"



INGRESS_CONTROLLER_VALUES_FILE="$REPO_ROOT/infra/helm-values/ingress-nginx-values-local.yaml"

echo "======================================================="
echo "📦 4. Deploying Ingress Nginx"
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update ingress-nginx
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
  -n ingress-controller --create-namespace \
  -f $INGRESS_CONTROLLER_VALUES_FILE




  JAEGER_VALUES_FILE="$REPO_ROOT/infra/helm-values/jaeger-values-local.yaml"
  helm repo add jaegertracing https://jaegertracing.github.io/helm-charts
  helm repo update jaegertracing
  helm upgrade --install jaeger jaegertracing/jaeger \
    -n payment --create-namespace \
    -f "$JAEGER_VALUES_FILE"





  OTEL_VALUES_FILE="$REPO_ROOT/infra/helm-values/opentelemetry-collector-values-local.yaml"
  helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts
  helm repo update open-telemetry
  helm upgrade --install my-opentelemetry-collector open-telemetry/opentelemetry-collector \
    -n payment \
    -f "$OTEL_VALUES_FILE"



echo "========================================================"
echo "✅ All local external infrastructure components deployed sequentially."
echo "Check progress via: kubectl get pods -A"
