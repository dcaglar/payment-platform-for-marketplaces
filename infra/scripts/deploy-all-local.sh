#!/usr/bin/env bash
set -euo pipefail

trap 'echo "❌ Deployment failed on line $LINENO. Command: $BASH_COMMAND"' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/../.."
cd "$REPO_ROOT"

echo "🚀 Starting complete local deployment of the Payment Platform..."

echo "📦 Step 1/4: Building all Docker images..."
"$SCRIPT_DIR/build-all-payment-platform-images-and-push.sh"

echo "🔧 Step 2/4: Deploying external infrastructure (Redis, Keycloak, Kafka)..."
"$SCRIPT_DIR/deploy-all-external-infra-local.sh"


echo "⚙️ Step 4/4: Deploying payment platform services to local..."
"$SCRIPT_DIR/deploy-payment-platform-services-local.sh"

echo "✅ All deployment manifests successfully submitted to local Kubernetes."
echo "   Kubernetes is now natively resolving dependencies via initContainers."
echo "   You can track progress via: kubectl get pods -n payment -w"
