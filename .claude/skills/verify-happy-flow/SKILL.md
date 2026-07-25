---
name: verify-happy-flow
description: Full pre-merge verification that a code change is not breaking anything. Runs unit tests, then integration tests, then rebuilds all 4 service images from the CURRENT code, then runs the end-to-end PaymentFlowE2EIntegrationTest (createPayment→authorize→SETTLED, milestones M0–M13, double-entry ledger). Use after changing service / domain / application code, before merging, or when asked "does the happy flow still work / did I break anything".
---

# Verify happy flow (unit → integration → build images → e2e)

Four stages, cheapest first so a break fails fast before the expensive image build + e2e. Run them **in order**; stop and report at the first stage that fails.

This is long-running (integration tests + several-minute image build + ~2-minute e2e; edge-worker dispatch has a 30s initial delay, settlement waits up to 120s). Prefer running each command in the background and reporting when it finishes.

## Preconditions
- Docker / OrbStack is running (integration tests + e2e use Testcontainers).
- Logged in to Docker Hub — stage 3's script also `docker push`es and its `set -e` aborts on a push-auth failure. Run `docker login` or export `DOCKER_TOKEN` first. (The local image is built before the push regardless.)
- Run everything from the repo root.

## Stages (in order — stop at first failure)

**1. Unit tests** (Surefire, mocks only, no containers):
```bash
mvn -B clean test
```

**2. Integration tests** (Failsafe + Testcontainers):
```bash
mvn -B clean verify -DskipUnitTests=true -Ddocker.client.api.version=1.44
```

**3. Rebuild all 4 service images from current code** — bakes your change into `dcaglar1987/{payment-service,payment-edge-workers,payment-central-relay,payment-consumers}:latest`, which the e2e boots:
```bash
bash infra/scripts/build-all-payment-platform-images-and-push.sh
```
Wait for `✅ Batch 2 complete.` The **images** run your code — skipping this stage means the e2e tests stale images, not your change (the #1 mistake).

**4. End-to-end happy-flow test:**
```bash
# e2e-tests is a standalone, non-reactor module; ensure its SNAPSHOT deps are in the local repo first
mvn -pl common,payment-domain -am install -Dmaven.test.skip=true
mvn -f e2e-tests/pom.xml verify
```
⚠️ Always use `-f e2e-tests/pom.xml`, never `-pl e2e-tests` (it's intentionally not a reactor module; `-pl` fails). Ignore the stale `mvn -pl e2e-tests` hint in the test header comment.

## Success criteria
Every stage ends `BUILD SUCCESS`. Stage 4 shows `Tests run: 1, Failures: 0, Errors: 0` — the payment reached `Payment.status = SETTLED` with all journal types recorded and every journal balanced.

## On failure — where to look
- **Stage 1/2 fails:** a unit/integration regression — read the failing `*Test`/`*IntegrationTest`; don't proceed to image build.
- **Stage 4 fails:** the milestone in the first failing assertion localizes the module:
  - **M0–M2** (intent CREATED/AUTHORIZED, edge outbox row) → `payment-service`
  - **M3** (edge outbox → `SENT`) → `payment-edge-workers` (forwarding)
  - **M4–M5** (central outbox payload → `SENT`) → `payment-central-relay` (T_Safe + publish)
  - **M6–M13** (Payment AUTHORIZED→SETTLED; journal AUTHORIZATION/CAPTURE/INTERNAL_TRANSFER/SETTLEMENT; balanced postings) → `payment-consumers` (ledger)
  Read the assertion's `withFailMessage`, query the container DBs (the test's `edgeScalar`/`centralScalar` helpers show the SQL), and re-confirm stage 3 actually rebuilt the images.