#!/usr/bin/env bash
# Secret scanning (gitleaks) + vulnerable dependencies and misconfigurations (Trivy), via Docker: nothing to install.
# Findings that existed when scanning was introduced are listed in .gitleaksignore / .trivyignore and ignored;
# only new ones fail (exit code 1). CI runs the same checks (.github/workflows/ci.yml, job security-scan).
#
#   infra/scripts/security-scan.sh
#
# Code quality (detekt) is not here: it runs in the Maven build (mvn verify).
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"
failed=0

echo "==> gitleaks: secrets in the git history"
if ! docker run --rm -v "$REPO_ROOT:/repo" zricethezav/gitleaks:latest \
    git /repo --redact --no-banner; then
  failed=1
fi

echo "==> gitleaks: secrets in uncommitted changes"
if ! docker run --rm -v "$REPO_ROOT:/repo" zricethezav/gitleaks:latest \
    git /repo --pre-commit --redact --no-banner; then
  failed=1
fi

# Trivy reads the dependencies from your local Maven cache (~/.m2) and stays offline: downloading every pom
# from Maven Central gets the IP rate-limited (429). Run `mvn install` first so the cache is complete.
echo "==> Trivy: HIGH/CRITICAL vulnerabilities (fix available) and misconfigurations"
if ! docker run --rm -v "$REPO_ROOT:/repo" -v "$HOME/.m2:/root/.m2:ro" -v trivy-cache:/root/.cache \
    aquasec/trivy:latest fs /repo \
    --offline-scan --scanners vuln,misconfig --severity HIGH,CRITICAL --ignore-unfixed \
    --ignorefile /repo/.trivyignore \
    --skip-dirs /repo/node_modules --skip-dirs /repo/mor-backoffice/node_modules --skip-dirs /repo/checkout-demo/node_modules \
    --table-mode detailed --exit-code 1 --quiet; then
  failed=1
fi

if [ "$failed" -ne 0 ]; then
  echo "❌ New findings (see above). Fix them, or if one is a false positive, add it to .gitleaksignore / .trivyignore."
  exit 1
fi
echo "✅ No new findings"
