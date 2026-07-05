#!/usr/bin/env bash
# Deploys all external infrastructure (Keycloak, Redis, Kafka, KEDA) to Azure AKS.
# Mirrors deploy-all-external-infra-local.sh exactly — azure environment argument only.
#
# Usage: ./deploy-all-external-infra-azure.sh
set -euo pipefail
echo "az login is being performed"
az login
echo "active subscription is being set to 7ff93b69-058b-4fee-8dc3-933e9d0d1b86"
az account set --subscription "7ff93b69-058b-4fee-8dc3-933e9d0d1b86"
az aks get-credentials --resource-group rg-payment-platform-loadtest --name aks-payment-loadtest --overwrite-existing
CURRENT_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "none")
# If the context is NOT the one we expect(aks-payment-loadtest, then abort!)
if [[ "$CURRENT_CONTEXT" != "aks-payment-loadtest" ]]; then
  echo "❌ Current context is '$CURRENT_CONTEXT'. Refusing to deploy to the wrong cluster!"
  echo "make ure the current context is aks-payment-loadtest"
    echo "Run 'az aks get-credentials --resource-group rg-payment-platform-loadtest --name aks-payment-loadtest --overwrite-existing' manually,then rerin scripts "
  exit 1
fi
echo "In order to set connect your local terminal to a remote Azure Kubernetes Service (AKS) cluster aks-payment-loadtest💡  az aks get-credentials --resource-group rg-payment-platform-loadtest --name aks-payment-loadtest was executed"
echo "From now on kubectl commands on your local terminal is executed against $CURRENT_CONTEXT"
echo "ℹ️  Deploying to verified context: $CURRENT_CONTEXT"
echo "🚀 Deploying all external infrastructure (Keycloak, Redis, Kafka, KEDA) to Azure..."


# 1. Keycloak
echo "Sending a deployment request of KEYCLOAK to Azure helm..."
"$SCRIPT_DIR/deploy-external-infra-azure.sh" keycloak
echo "Deployment request of KEYCLOAK submitted to Azure helm."

# 2. Redis
echo "Sending a deployment request of REDIS to Azure helm..."
"$SCRIPT_DIR/deploy-external-infra-azure.sh" redis
echo "Deployment request of REDIS submitted to Azure helm."

# 3. Kafka
echo "Sending a deployment request of KAFKA to Azure helm..."
"$SCRIPT_DIR/deploy-external-infra-azure.sh" kafka
echo "Deployment request of KAFKA submitted to Azure helm."

# 4. KEDA — required on Azure for payment-consumers autoscaling
echo "Sending a deployment request of KEDA to Azure helm..."
"$SCRIPT_DIR/deploy-external-infra-azure.sh" keda
echo "Deployment request of KEDA submitted to Azure helm."

echo ""
echo "✅ All external infrastructure manifests successfully submitted to Azure Kubernetes via helm."
echo "Kubernetes is now resolving dependencies natively via initContainers."
echo "Check progress via: kubectl get pods -n payment -w"
