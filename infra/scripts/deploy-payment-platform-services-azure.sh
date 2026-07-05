#!/usr/bin/env bash
# Deploys all payment platform services to Azure AKS.
# Azure mirror of: deploy-payment-platform-services-local.sh
#
# Usage: ./deploy-payment-platform-services-azure.sh
set -euo pipefail

trap 'echo "❌ Azure payment platform services deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR
NS="payment"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/../.."
cd "$REPO_ROOT"

echo "🛡️  Checking Kubernetes context..."
CURRENT_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "none")
if [[ "$CURRENT_CONTEXT" == "orbstack" ]]; then
  echo "❌ Current context is 'orbstack' — refusing to deploy azure workloads to local cluster."
  echo "💡 Run: az aks get-credentials --resource-group rg-payment-platform-loadtest --name aks-payment-loadtest"
  exit 1
fi
echo "ℹ️  Deploying to context: $CURRENT_CONTEXT"

echo "🚀 Deploying all payment platform services to Azure..."

# 1. Ingress Controller (Nginx), installed for load balancing support of payment-service
echo "Sending a deployment request of ingress LOAD BALANCER controller to Azure helm..."
"$SCRIPT_DIR/deploy-external-infra.sh" nginx-ingress-controller azure
echo "Deployment request of ingress LOAD BALANCER controller was submitted to Azure helm."

# 2. Payment Edge Cell (payment-service and local edge-db initialized with necessary
#    username and password, and liquibase performs initial table creation)
echo "Sending a deployment request of payment-edge-cell chart (payment-service and local edge-db) to Azure helm..."
"$SCRIPT_DIR/deploy.sh" payment-edge-cell azure
echo "Deployment request of payment-edge-cell chart was submitted to Azure helm."

# 3. Payment Edge Workers (Asynchronous OutboxForwarder job moves local outbox to
#    central outbox, and also Outbox Maintenance job)
echo "Sending a deployment request of payment-edge-workers chart to Azure helm..."
"$SCRIPT_DIR/deploy.sh" payment-edge-workers azure
echo "Deployment request of payment-edge-workers chart (LocalOutboxStoreAndForwardJob and LocalOutboxMaintenanceJob) was submitted to Azure helm."

# 4. Central DB — initialized with custom users/roles, liquibase creates schema
echo "Sending a deployment request of central-db chart to Azure helm..."
"$SCRIPT_DIR/deploy.sh" central-db azure
echo "Deployment request of central-db chart was submitted to Azure helm."

# 5. Payment Consumers
echo "Sending a deployment request of payment-consumers chart to Azure helm..."
"$SCRIPT_DIR/deploy.sh" payment-consumers azure
echo "Deployment request of payment-consumers chart was submitted to Azure helm."

# 6. Payment Central Relay (OutboxRelayJob and CentralOutboxMaintenanceJob)
echo "Sending a deployment request of payment-central-relay chart to Azure helm..."
"$SCRIPT_DIR/deploy.sh" payment-central-relay azure
echo "Deployment request of payment-central-relay chart (OutboxRelayJob and CentralOutboxMaintenanceJob) was submitted to Azure helm."

echo ""
echo "✅ All manifests successfully submitted to Azure Kubernetes via helm."
echo "Kubernetes is now resolving dependencies natively via initContainers."
echo "Check progress via: kubectl get pods -n payment -w"
