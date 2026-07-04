#!/usr/bin/env bash
set -euo pipefail

trap 'echo "❌ Local external infra deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR
NS="payment"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/../.."
cd "$REPO_ROOT"


kubectl config set-context orbstack

echo "🛡️  Checking Kubernetes context..."
CURRENT_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "none")
if [[ "$CURRENT_CONTEXT" != "orbstack" ]]; then
  echo "⚠️  Current context is '$CURRENT_CONTEXT', but this script requires 'orbstack'."
  if kubectl config get-contexts $NS >/dev/null 2>&1; then
    echo "🔄 Switching context to 'orbstack'..."
    kubectl config use-context orbstack
  else
    echo "❌ orbstack context not found! Is OrbStack running with Kubernetes enabled?Setting/using context orbstack"
        kubectl config set-context orbstack
        kubectl config use-context orbstack

    exit 1
  fi
fi



echo "🚀 Deployin all external infrasracture(Redis,Keycloak,Kafka) locally"







# 1. Keycloak
echo "Sending a deployment request of  KEYCLOAK to  local helm "

"$SCRIPT_DIR/deploy-external-infra.sh" keycloak local
echo "Deployment request of  KEYCLOAK was submitted to local helm"

# 2.Redis
echo "Sending a deployment request of  REDIS to  local helm "
"$SCRIPT_DIR/deploy-external-infra.sh" redis local
echo "Deployment request of  REDIS was submitted to local helm"

# 3.Kafka
echo "Sending a deployment request of  KAFKA to  local helm "
"$SCRIPT_DIR/deploy-external-infra.sh" kafka local
echo "Deployment request of  kafka  was submitted to local helm"



# 4.keda we should not really sent or use keda in local
echo "Sending a deployment request of  KEDA to  local helm "
#"$SCRIPT_DIR/deploy-external-infra.sh" keda local
echo "Deployment request of  KEDA was submitted to local helm"

echo ""
echo "✅ All manifests successfully submitted to local Kubernetes via helm"
echo "Kubernetes is now resolving dependencies natively via initContainers."
echo "Check progress via: kubectl get pods -n payment -w"
