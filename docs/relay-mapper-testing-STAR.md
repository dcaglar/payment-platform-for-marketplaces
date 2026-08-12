# Relay Outbox Mapper — Test Strategy & Claim-Protocol Suite (STAR)

> Status: **DONE.** All suites green. This is the record of what was built and why,
> plus the findings report.

---

## S — Situation

`payment-central-relay` is the ONLY component allowed to publish to Kafka. Its correctness
rests on the claim state machine implemented entirely in `CentralOutboxRelayMapper.xml`:

```
            findEligible (UPDATE … FOR UPDATE SKIP LOCKED … RETURNING, atomic claim)
  ┌──────┐  sets claimed_by / claimed_at                      ┌────────────┐
  │ NEW  │ ────────────────────────────────────────────────▶ │ PROCESSING │
  └──────┘                                                   └────────────┘
    ▲  ▲                                                        │      │ Kafka ACK →
    │  │  unclaimSpecific (guarded: claimed_by = workerId)      │      │ markDispatched
    │  └────────────────────────────────────────────────────────┘      ▼
    │     reclaimStuck (claimed_at < now-120s, every 120s)         ┌──────┐
    └──────────────────────────────────────────────────────────────│ SENT │ terminal
          crashed worker → row parked 2–4 min → duplicate window   └──────┘
```

Problems found: the SQL had **zero direct tests** (`OutboxRelayJobTest.kt` was an empty
file); the three hand-written `schema-test.sql` files were **orphaned** (zero references)
and **stale** (missing `partition_key`/`event_id`/`parent_event_id`, wrong column order) —
they froze when the legacy mapper ITs were deleted, and maintaining schema copies separately
from the Liquibase changelogs was inherently drift-prone.

## T — Task

1. A platform-wide integration-test strategy where **the Liquibase changelogs are the ONLY
   schema definition anywhere** — no copies, no generation, no drift possible.
2. A correctness + concurrency IT suite for every statement in the relay mapper.
3. Worker unit tests + ordering-layer guard tests.
4. Findings report — defects documented, never fixed (zero `src/main` changes).

## A — What was built

### The strategy: one changelog, one fixture, three layers

```
              charts/central-db/db/changelog.central.xml   (single source of truth)
              charts/payment-edge-cell/db/changelog.edge.xml
                               │
      ┌────────────────────────┼────────────────────────────┐
      ▼                        ▼                            ▼
 PROD (helm liquibase job)   E2E (E2eSupport.migrate)   MODULE ITs (TestDatabases fixture)
```

| Layer | Naming | Runner | Uses |
|---|---|---|---|
| Unit | `XxxTest.kt` | Surefire (`mvn test`) | mocks only |
| Integration | `XxxIntegrationTest.kt` + `@Tag("integration")` | Failsafe (`mvn verify`) | Testcontainers, ONE adapter ↔ ONE real backing service |
| E2E | `PaymentFlowE2EIntegrationTest` (own module) | `mvn verify` | real service images, M0–M13 |

### `TestDatabases` (common-test) — the one place test DBs are initialized
`common-test/src/test/kotlin/com/dogancaglar/paymentservice/util/TestDatabases.kt`:
singleton `centralDb()` / `edgeDb()` Postgres 17 containers migrated with the REAL
changelogs (same 15-line Liquibase mechanism as `E2eSupport.migrate`), + a DEFAULT
partition per partitioned parent (runtime jobs do this in prod), + `truncateAll()` for
@BeforeEach isolation (preserves Liquibase bookkeeping + `account_directory` seed data),
+ `connection()` helper. Consumers declare their own testcontainers/liquibase test deps
(test-jar deps aren't transitive). **Change a changelog → every test picks it up
automatically. Nothing else to maintain — the pain that killed the old schema copies is
structurally gone.**

Deleted: the 3 orphaned `schema-test.sql` files, the short-lived regeneration script idea,
and the empty `OutboxRelayJobTest.kt`.

### The suites (all green)
- **`CentralOutboxRelayMapperIntegrationTest` — 28 tests** covering: tSafe boundary
  (inclusive, ms precision), oldest-first selection, ascending result order (the outer
  `ORDER BY` is load-bearing — `RETURNING` order is undefined), claim column contract,
  hydration round-trip, claim invisibility, **SKIP LOCKED disjointness** (open-TX session A
  vs session B: zero overlap, no blocking), cross-partition global oeid order, non-UTC JVM
  timezone run, **poison-pill NULL-parent** (documents the wedge risk), reclaim strict
  threshold/status scoping/zero-threshold, countEligible 10k saturation, markDispatched
  terminal/composite-key/unguarded-doc, unclaim anti-steal guard + interleavings
  (stale-worker-after-reclaim, after-mark no-op), computeTSafe slowest-edge-wins +
  fail-open NULL, deleteWatermark gate-widening, and 4 cross-statement walks (lifecycle,
  crash/duplicate window, clean failure, ordering-responsibility boundary).
- **`CentralOutboxDispatchWorkerTest` — 5 tests**: sequential publish+mark per aggregate in
  oeid order; failure at N → unclaim N ONCE with the claiming workerId, **tail parked**
  (neither published nor unclaimed → reclaimer's job); cross-aggregate isolation; unclaim
  errors swallowed; empty batch submits nothing.
- **`RawEventPublisherTest` — 3 tests** (ordering L3): key = partitionKey, topic from the
  metadata registry (never caller-supplied), parentEventId header, payload raw passthrough.
- **`KafkaProducerConfigTest` — 2 tests** (ordering L4): `ENABLE_IDEMPOTENCE=true` paired
  with `MAX_IN_FLIGHT≤5`; StringSerializer on the raw path.

### Verified seeding rule (the corrected analysis)
MyBatis hydrates the immutable `OutboxEventEntity` in two phases: all-args constructor
bound by RESULT-SET COLUMN ORDER (garbage for the aggregateId/eventId/parentEventId trio),
then resultMap property mappings overwrite every field by name → final entity correct.
**Production is fine** (confirmed by load test + the hydration round-trip test). The only
failure mode is a NULL `parent_event_id` (constructor null-check) — impossible today because
`EventEnvelopeFactory` does `parentEventId ?: id`. Test rows therefore always seed non-null
parents; one dedicated test pins the NULL behavior as documentation.

## R — Results

```
mvn clean verify -pl payment-central-relay                     BUILD SUCCESS  (10 unit + 28 IT)
mvn clean verify -pl payment-service,payment-consumers,
                     payment-infrastructure                    BUILD SUCCESS  (19+14 / 4 / 37)
```
Machine-local prerequisite (not committed): `~/.testcontainers.properties` now points at
OrbStack's socket-activated docker socket (was pinning Docker Desktop's dead socket) —
this also fixed the pre-existing Redis IT.

### Findings report (documented, NOT fixed — candidate follow-ups)

| # | Finding | Evidence |
|---|---|---|
| 1 | `parent_event_id` nullable in DDL but unhydratable when NULL — invariant lives in a factory two modules away. Follow-up: NOT NULL constraint, or `<constructor>` resultMap, or nullable entity field | `findEligible with a NULL parent_event_id row throws…` |
| 2 | Poison-pill wedge: NULL-parent row → claim commits, mapping throws → PROCESSING → reclaim → re-claim → throws forever | same test: row left PROCESSING after the exception |
| 3 | `20260708-*` changesets exist in `charts/central-db/db/` but are NOT included in `changelog.central.xml` → never applied to fresh DBs | liquibase run log: neither changeset executed |
| 4 | No index supports the claim query's `ORDER BY oeid` → backlog recovery sorts the whole NEW set per poll. Suggest partial index `(oeid) WHERE status='NEW'` | DDL indexes: status/created_at/claimed_at only |
| 5 | `computeTSafe()` fails OPEN: empty `edge_watermarks` → NULL → worker falls back to `now()`, silently disabling the completeness gate; `deleteWatermark` widens it | `computeTSafe should return null…`, `deleteWatermark of the lagging edge…` |
| 6 | `markDispatched` has no claimed_by/status guard (asymmetric with `unclaimSpecific`) | `markDispatched has no claimed_by or status guard…` |
| 7 | `reclaimStuckClaims` doesn't touch `updated_at` (inconsistent with `unclaimSpecific`) | mapper XML diff of the two UPDATEs |
| 8 | `countEligible` saturates at 10 000 → backlog gauge can't distinguish 10k from 500k | `countEligible should…saturate at 10000` |
| 9 | Lease math: 120s staleness vs executor (32 thr, queue 500, CallerRuns) — a backed-up queue can outlive the lease → reclaim flips in-flight rows → duplicate storms | config values + crash-walk test |
| 10 | Failure parking latency asymmetry: failed entry re-eligible in ≤5s, its parked tail only after 2–4 min reclaim | worker test `…PARK the tail` |
| 11 | Dead code/fossils: `RetryDispatcherScheduler` fully commented out; root pom manages `mybatis-spring-boot-starter-test` with zero usages | source inspection |
