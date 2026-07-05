#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <service-name> <environment>"
  echo "Example: $0 payment-central-relay local"
  exit 1
}

kubectl config use-context orbstack 2>/dev/null || true
RELEASE_NAME=${1:-}
ENV=${2:-}
NS=${3:-"payment"}


if [ -z "$RELEASE_NAME" ] || [ -z "$ENV" ]; then
  usage
fi

echo "🗑️ Deleting $RELEASE_NAME from $ENV environment on name space $NS"

# Standardized helm uninstall options for fast purges (timeout 60s instead of 5 minutes, ignore-not-found spacing fixed)
helm uninstall "$RELEASE_NAME" \
  -n "$NS" \
  --wait \
  --timeout=60s \
  --ignore-not-found

echo "✅ Deletion of $RELEASE_NAME complete on $ENV  on namespace $NS"
