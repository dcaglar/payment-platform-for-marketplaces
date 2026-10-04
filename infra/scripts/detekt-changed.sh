#!/usr/bin/env bash
# Runs detekt on only the Kotlin files you changed or added locally (uncommitted), module by module.
# Committed files are not touched.
#
#   infra/scripts/detekt-changed.sh          fix what detekt can fix itself (wrapping, spacing, import order),
#                                            then list what is left
#   infra/scripts/detekt-changed.sh --check  only list the findings, change nothing
#
# What is left after fixing (long lines, unused code, ...) needs a human. A module with findings left ends in
# "BUILD FAILURE": that is expected, the fixes are written to the files anyway.
# The full check (all files, against each module's detekt-baseline.xml) runs in `mvn verify`.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

AUTO_CORRECT=true
if [ "${1:-}" = "--check" ]; then
  AUTO_CORRECT=false
fi

MODULES="common payment-domain common-test payment-application common-kafka payment-infrastructure common-db
payment-service payment-edge-workers payment-consumers payment-central-relay e2e-tests"

for m in $MODULES; do
  # changed + new (untracked) .kt files of this module that still exist (not deleted)
  files=""
  for f in $(git status --porcelain -uall -- "$m" | awk '{print $NF}' | grep '\.kt$'); do
    if [ -f "$f" ]; then
      files="$files,$REPO_ROOT/$f"
    fi
  done
  files="${files#,}"
  # without a final newline detekt's auto-fix crashes on the file ("source location line must be greater than 0")
  if [ "$AUTO_CORRECT" = "true" ]; then
    for f in ${files//,/ }; do
      if [ -s "$f" ] && [ -n "$(tail -c1 "$f")" ]; then
        echo >> "$f"
      fi
    done
  fi
  if [ -z "$files" ]; then
    continue
  fi

  echo "== $m"
  if [ "$m" = "e2e-tests" ]; then
    mvn -B -q -o -f e2e-tests/pom.xml detekt:check -Ddetekt.autoCorrect="$AUTO_CORRECT" -Ddetekt.input="$files"
  else
    mvn -B -q -o -pl "$m" detekt:check -Ddetekt.autoCorrect="$AUTO_CORRECT" -Ddetekt.input="$files"
  fi
done
