#!/bin/bash
# Usage: build-and-push.sh <module-name> [dockerhub-username] [tag]
# Example: ./build-and-push.sh payment-service dcaglar1987 latest
set -euo pipefail

trap 'echo "❌ Build script failed on line $LINENO. Command: $BASH_COMMAND"' ERR

export DOCKER_BUILDKIT=1

# Arguments
MODULE_NAME=${1:-}
DOCKERHUB_USER=${2:-dcaglar1987}
TAG=${3:-latest}

if [ -z "$MODULE_NAME" ]; then
  echo "Usage: $0 <module-name> [dockerhub-username] [tag]"
  echo "Example: $0 payment-service dcaglar1987 latest"
  exit 1
fi

# If DOCKER_TOKEN is set, use it to log in. Otherwise, assume the user is already logged in.
if [ -n "${DOCKER_TOKEN:-}" ]; then
  echo "Logging in to Docker Hub using DOCKER_TOKEN..."
  set +e
  LOGIN_OUTPUT=$(echo "$DOCKER_TOKEN" | docker login --username "$DOCKERHUB_USER" --password-stdin 2>&1)
  LOGIN_STATUS=$?
  set -e

  if [ $LOGIN_STATUS -ne 0 ]; then
    if echo "$LOGIN_OUTPUT" | grep -qi "already exists in the keychain"; then
      echo "⚠️ docker login threw a macOS keychain error, ignoring because authentication likely succeeded..."
    else
      echo "❌ Docker login failed:"
      echo "$LOGIN_OUTPUT"
      exit $LOGIN_STATUS
    fi
  else
    echo "✅ Docker login successful."
  fi
else
  echo "DOCKER_TOKEN is not set. Assuming you are already logged in to Docker Hub..."
fi

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

if [ ! -d "$MODULE_NAME" ]; then
  echo "Error: Module directory '$MODULE_NAME' not found in repo root ($REPO_ROOT)."
  exit 1
fi

echo "Building $MODULE_NAME "
docker build -f "$MODULE_NAME/Dockerfile" -t "$DOCKERHUB_USER/$MODULE_NAME:$TAG" .

echo "Pushing $DOCKERHUB_USER/$MODULE_NAME:$TAG to Docker Hub..."
docker push "$DOCKERHUB_USER/$MODULE_NAME:$TAG"

echo "✅ Build and push complete: $DOCKERHUB_USER/$MODULE_NAME:$TAG"
