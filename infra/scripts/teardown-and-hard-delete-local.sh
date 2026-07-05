#!/usr/bin/env bash
set -euo pipefail

trap 'echo "❌ Teardown failed on line $LINENO. Command: $BASH_COMMAND"' ERR

echo "🔥 INITIATING HARD TEARDOWN OF ORB CLUSTER LOCAL ENVIRONMENT 🔥"
echo "This is a one-way command. Smashing everything now..."

echo "🔄 Ensuring we are on the orbstack context..."
kubectl config use-context orbstack || echo "⚠️ Could not switch context, continuing anyway..."

# 1. Smash Helm Releases (no hooks, no waiting)
echo "💥 Forcibly uninstalling all Helm releases..."
helm ls -a -n payment -q 2>/dev/null | xargs -r helm uninstall -n payment --no-hooks --wait=false || true
helm ls -a -n ingress-controller -q 2>/dev/null | xargs -r helm uninstall -n ingress-controller --no-hooks --wait=false || true
helm ls -a -n monitoring -q 2>/dev/null | xargs -r helm uninstall -n monitoring --no-hooks --wait=false || true

# 2. Smash finalizers on StatefulSets and PVCs (prevents hanging deletions)
echo "💥 Stripping finalizers from PVCs to prevent hang..."
kubectl get pvc -n payment -o name 2>/dev/null | xargs -I {} kubectl patch {} -n payment -p '{"metadata":{"finalizers":null}}' --type=merge || true

# 3. Smash Namespaces
echo "💥 Forcibly deleting namespaces (0 grace period)..."
kubectl delete namespace payment --force --grace-period=0 --wait=false || true
kubectl delete namespace ingress-nginx --force --grace-period=0 --wait=false || true
kubectl delete namespace monitoring --force --grace-period=0 --wait=false || true

# 4. Smash all PVs
echo "💥 Stripping finalizers and obliterating all Persistent Volumes..."
kubectl get pv -o name 2>/dev/null | xargs -I {} kubectl patch {} -p '{"metadata":{"finalizers":null}}' --type=merge || true
kubectl get pv -o name 2>/dev/null | xargs -r kubectl delete --force --grace-period=0 || true

# 5. Smash Helm Repositories
echo "💥 Forcibly removing local Helm repositories..."
helm repo remove bitnami 2>/dev/null || echo "ℹ️ bitnami repo already removed"
helm repo remove prometheus-community 2>/dev/null || echo "ℹ️ prometheus-community repo already removed"
helm repo remove kedacore 2>/dev/null || echo "ℹ️ kedacore repo already removed"
helm repo remove ingress-nginx 2>/dev/null || echo "ℹ️ ingress-nginx repo already removed"

echo "☠️  SMASH COMPLETE. The local environment has been obliterated."
