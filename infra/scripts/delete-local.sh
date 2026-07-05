#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: delete-local.sh <RELEASE_NAME>"
  echo "Example: delete-local.sh  "
  exit 1
}

kubectl config use-context orbstack 2>/dev/null || true
RELEASE_NAME=${1:-}


if [ -z "$RELEASE_NAME" ] || [ -z "$ENV" ]; then
  usage
fi

echo "🗑️ Deleting $RELEASE_NAME from local environment on name space $NS"

# Standardized helm uninstall options for fast purges (timeout 60s instead of 5 minutes, ignore-not-found spacing fixed)
helm uninstall "$RELEASE_NAME" \
  -n "$NS" \
  --wait \
  --timeout=60s \
  --ignore-not-found

echo "✅ Deletion of $RELEASE_NAME complete on $ENV  on namespace $NS"
