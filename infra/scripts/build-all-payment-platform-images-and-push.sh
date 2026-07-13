#!/usr/bin/env bash
set -euo pipefail

NS="payment"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/../.."
cd "$REPO_ROOT"

echo "DIR is $REPO_ROOT $SCRIPT_DIR"

echo "🚀 Building payment-service and payment-edge-workers in parallel (Batch 1/2)..."
"$SCRIPT_DIR/build-and-push-local.sh" payment-service dcaglar1987 latest &
PID1=$!
"$SCRIPT_DIR/build-and-push-local.sh" payment-edge-workers dcaglar1987 latest &
PID2=$!

wait $PID1
wait $PID2
echo "✅ Batch 1 complete."

echo "🚀 Building payment-central-relay and payment-consumers in parallel (Batch 2/2)..."
"$SCRIPT_DIR/build-and-push-local.sh" payment-central-relay dcaglar1987 latest &
PID3=$!
"$SCRIPT_DIR/build-and-push-local.sh" payment-consumers dcaglar1987 latest &
PID4=$!

wait $PID3
wait $PID4
echo "✅ Batch 2 complete."


