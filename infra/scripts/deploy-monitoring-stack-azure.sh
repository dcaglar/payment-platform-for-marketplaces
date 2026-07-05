#!/bin/bash
set -euo pipefail

trap 'echo "❌ Azure monitoring stack deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/../.."
cd "$REPO_ROOT"

VALUES_FILE="$REPO_ROOT/infra/helm-values/monitoring-stack-values-azure.yaml"

echo "▶️  Deploying kube-prometheus-stack with values: $VALUES_FILE"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

helm upgrade --install prometheus-stack prometheus-community/kube-prometheus-stack \
  -n monitoring --create-namespace -f "$VALUES_FILE"

echo "✅ kube-prometheus-stack deployment requested."


# 3.Kafka exporter
echo "Sending a deployment request of  KAFKA exporter to  azure helm "
"$SCRIPT_DIR/deploy-external-infra.sh" kafka-exporter azure
echo "Deployment request of  KAFKA exporter was submitted to azure helm"

# 3.postgre sql exporter
echo "Sending a deployment request of  POSTGRESQL exporter to  azure helm "
"$SCRIPT_DIR/deploy-external-infra.sh" postgresql-exporter azure
echo "Deployment request of  POSTGRESQL exporter was submitted to azure helm"

echo "🚀 Toggling ServiceMonitors to 'true' in application Helm values..."
yq -i '.controller.metrics.serviceMonitor.enabled = true' "$REPO_ROOT/infra/helm-values/nginx-ingress-controller-values-azure.yaml" || true
yq -i '.serviceMonitor.enabled = true' "$REPO_ROOT/charts/payment-edge-cell/azure/values.yaml" || true
yq -i '.serviceMonitor.enabled = true' "$REPO_ROOT/charts/payment-consumers/azure/values.yaml" || true
yq -i '.serviceMonitor.enabled = true' "$REPO_ROOT/charts/payment-central-relay/azure/values.yaml" || true
yq -i '.serviceMonitor.enabled = true' "$REPO_ROOT/charts/payment-edge-workers/azure/values.yaml" || true
echo "✅ Monitoring switched ON! Applications will now deploy with metrics enabled."




