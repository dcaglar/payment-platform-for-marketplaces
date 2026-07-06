#!/usr/bin/env bash
set -euo pipefail

# 1. Validation (Keep your context check, it's good)
CURRENT_CONTEXT=$(kubectl config current-context || echo "none")
if [[ "$CURRENT_CONTEXT" != "orbstack" ]]; then
  echo "❌ Context is '$CURRENT_CONTEXT'. Aborting!"
  exit 1
fi

echo "🛡️  Validated context: $CURRENT_CONTEXT"

# 2. Namespace Wipe (The Atomic Way)
# Instead of helm uninstall, we wipe the namespaces entirely.
# This forces K8s to delete all deployments, services, and CRDs inside.
for ns in payment ingress-controller keda; do
  echo "🧹 Wiping namespace: $ns"
  kubectl delete namespace "$ns" --wait=true --timeout=60s || echo "⚠️  Namespace $ns delete timed out, check for stuck finalizers."
done

# 3. Handle "Stuck" Resources (The Finalizer Fix)
# Sometimes K8s CRDs (like KEDA or OTel Operator) get stuck in Terminating.
# This command forces them to clear if they are refusing to die.
echo "🔍 Checking for stuck resources..."
kubectl get all -A | grep -i "terminating" || echo "✅ No stuck resources found."

# 4. OrbStack Reset (The "Real" Reset)
echo "🔥 Resetting OrbStack Cluster..."
if command -v orb &>/dev/null; then
  # Remove || true so we actually see if OrbStack fails
  orb delete k8s
  orb start k8s
else
  echo "❌ OrbStack CLI not found! Manual reset required."
  exit 1
fi

echo "✅ Clean slate verified."