#!/bin/bash
set -euo pipefail

echo "🛡️ Verifying Kubernetes context..."
CURRENT_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "none")
if [[ "$CURRENT_CONTEXT" != "aks-payment-loadtest" ]]; then
  echo "❌ Current context is '$CURRENT_CONTEXT'. Refusing to delete the wrong cluster!"
  exit 1
fi

echo "🚀 Uninstalling all Payment Platform Services..."
helm uninstall -n payment payment-edge-cell --ignore-not-found --wait --timeout 5m
helm uninstall -n payment payment-edge-workers --ignore-not-found
helm uninstall -n payment payment-central-relay --ignore-not-found
helm uninstall -n payment payment-consumers --ignore-not-found
helm uninstall -n payment central-db --ignore-not-found --wait --timeout 5m

echo "🚀 Uninstalling External Infrastructure..."
helm uninstall -n ingress-nginx ingress-nginx --ignore-not-found --wait --timeout 5m
helm uninstall -n payment keycloak --ignore-not-found --wait --timeout 5m
helm uninstall -n payment kafka --ignore-not-found --wait --timeout 5m
helm uninstall -n payment redis --ignore-not-found --wait --timeout 5m
helm uninstall -n keda keda --ignore-not-found

echo "🚀 Uninstalling Monitoring Stack..."
helm uninstall -n monitoring prometheus-stack --ignore-not-found --wait --timeout 5m
helm uninstall -n monitoring central-db-postgres-exporter --ignore-not-found

echo "🚀 Deleting ALL PVCs (Triggers Azure Disk Deletion)..."
kubectl delete pvc --all -n payment --ignore-not-found
kubectl delete pvc --all -n monitoring --ignore-not-found

echo "✅ Graceful Kubernetes teardown complete. Azure disks and IPs are un-provisioning."
