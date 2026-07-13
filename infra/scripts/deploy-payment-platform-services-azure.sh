#!/usr/bin/env bash
# Deploys all payment platform services to Azure AKS.
# Azure mirror of: deploy-payment-platform-services-local.sh
#
# Usage: ./deploy-payment-platform-services-azure.sh
set -euo pipefail

trap 'echo "❌ Azure payment platform services deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/../.."
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

echo "🚀 Deploying all payment platform services to Azure..."

echo "========================================================"
echo "📦 1. Deploying central-db"
helm dependency update "$REPO_ROOT/charts/central-db"
helm secrets upgrade --install central-db "$REPO_ROOT/charts/central-db" \
  -n payment --create-namespace \
  -f "$REPO_ROOT/charts/central-db/values.yaml" \
  -f "$REPO_ROOT/charts/central-db/azure/values.yaml" \
  -f "secrets://$REPO_ROOT/central-db-sops-secrets.yaml"

echo "========================================================"
echo "📦 2. Deploying payment-edge-cell"
helm dependency update "$REPO_ROOT/charts/payment-edge-cell"
helm secrets upgrade --install payment-edge-cell "$REPO_ROOT/charts/payment-edge-cell" \
  -n payment --create-namespace \
  -f "$REPO_ROOT/charts/payment-edge-cell/values.yaml" \
  -f "$REPO_ROOT/charts/payment-edge-cell/azure/values.yaml" \
  -f "secrets://$REPO_ROOT/edge-cell-sops-secrets.yaml"

echo "========================================================"
echo "📦 3. Deploying payment-edge-workers"
helm dependency update "$REPO_ROOT/charts/payment-edge-workers"
helm secrets upgrade --install payment-edge-workers "$REPO_ROOT/charts/payment-edge-workers" \
  -n payment --create-namespace \
  -f "$REPO_ROOT/charts/payment-edge-workers/values.yaml" \
  -f "$REPO_ROOT/charts/payment-edge-workers/azure/values.yaml" \
  -f "secrets://$REPO_ROOT/edge-cell-sops-secrets.yaml" \
  -f "secrets://$REPO_ROOT/central-db-sops-secrets.yaml"

echo "========================================================"
echo "📦 4. Deploying payment-central-relay"
helm secrets dependency update "$REPO_ROOT/charts/payment-central-relay"
helm secrets upgrade --install payment-central-relay "$REPO_ROOT/charts/payment-central-relay" \
  -n payment --create-namespace \
  -f "$REPO_ROOT/charts/payment-central-relay/values.yaml" \
  -f "$REPO_ROOT/charts/payment-central-relay/azure/values.yaml" \
  -f "secrets://$REPO_ROOT/central-db-sops-secrets.yaml"

echo "========================================================"
echo "📦 5. Deploying payment-consumers"
helm dependency update "$REPO_ROOT/charts/payment-consumers"
helm secrets upgrade --install payment-consumers "$REPO_ROOT/charts/payment-consumers" \
  -n payment --create-namespace \
  -f "$REPO_ROOT/charts/payment-consumers/values.yaml" \
  -f "$REPO_ROOT/charts/payment-consumers/azure/values.yaml" \
  -f "secrets://$REPO_ROOT/central-db-sops-secrets.yaml"

echo "========================================================"
echo "✅ All Azure platform services deployed sequentially."
echo "Check progress via: kubectl get pods -A"
