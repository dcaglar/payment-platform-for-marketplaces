#!/usr/bin/env bash
# Deploys all external infrastructure (Keycloak, Redis, Kafka, KEDA, Ingress, Jaeger, OTEL) to Azure AKS.
#
# Usage: ./deploy-all-external-infra-azure.sh
set -euo pipefail

trap 'echo "❌ Azure external infra deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

echo "az login is being performed"
az login
echo "active subscription is being set to 7ff93b69-058b-4fee-8dc3-933e9d0d1b86"
az account set --subscription "7ff93b69-058b-4fee-8dc3-933e9d0d1b86"
az aks get-credentials --resource-group rg-payment-platform-loadtest --name aks-payment-loadtest --overwrite-existing

CURRENT_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "none")

if [[ "$CURRENT_CONTEXT" != "aks-payment-loadtest" ]]; then
  echo "❌ Current context is '$CURRENT_CONTEXT'. Refusing to deploy to the wrong cluster!"
  exit 1
fi

echo "🚀 Deploying all external infrastructure to Azure..."

echo "========================================================"
echo "📦 1. Deploying Keycloak"
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update bitnami
helm upgrade --install keycloak bitnami/keycloak \
  -n payment --create-namespace \
  -f "$REPO_ROOT/infra/helm-values/keycloak-values-azure.yaml" \
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
  -f "$REPO_ROOT/infra/helm-values/kafka-values-azure.yaml" \
  --version 32.3.14

echo "========================================================"
echo "📦 3. Deploying Redis"
helm upgrade --install redis bitnami/redis \
  -n payment --create-namespace \
  -f "$REPO_ROOT/infra/helm-values/redis-values-azure.yaml"

echo "========================================================"
echo "📦 4. Deploying KEDA"
helm repo add kedacore https://kedacore.github.io/charts
helm repo update kedacore
helm upgrade --install keda kedacore/keda \
  -n keda --create-namespace \
  --set nodeSelector.pool=central

echo "======================================================="
echo "📦 5. Deploying Ingress Nginx"
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update ingress-nginx
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
  -n ingress-controller --create-namespace \
  -f "$REPO_ROOT/infra/helm-values/ingress-nginx-values-azure.yaml"



echo "======================================================="
echo "📦 7. Deploying OpenTelemetry Collector"
helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts
helm repo update open-telemetry
helm upgrade --install my-opentelemetry-collector open-telemetry/opentelemetry-collector \
  -n payment --create-namespace \
  -f "$REPO_ROOT/infra/helm-values/opentelemetry-collector-values-azure.yaml"

echo "========================================================"
echo "✅ All Azure external infrastructure components deployed sequentially."
echo "Check progress via: kubectl get pods -A"
