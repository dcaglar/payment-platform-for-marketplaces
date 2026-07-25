# payment-edge-workers — Edge local→central forwarder (DEPLOYABLE, background-only)

Standalone Spring Boot app (`PaymentEdgeWorkersApplication`, has a `Dockerfile`) that runs in **its OWN pod with its own resources** — it is NOT co-located in the edge-cell pod (that pod holds `payment-service` + `edge-db` together). NO REST — its inbound adapters are `@Scheduled` jobs. It implements **stage 2 of the two-stage outbox**: poll the LOCAL edge-db outbox and forward rows to the CENTRAL db outbox. It never mutates domain state and never publishes to Kafka.

## Topology & scaling (1 worker ⟷ 1 edge-db)
- Each edge-workers instance is **pinned 1:1 to a single edge cell**: it is responsible for forwarding exactly ONE edge-db's local outbox. `POD_NAME=payment-edge-cell-<ordinal>` selects which edge-db it owns.
- So it does NOT scale freely — its replica count tracks the number of edge cells (one forwarder per edge-db). Adding an edge cell ⇒ add its matching edge-workers instance. Don't reason about it as a horizontally-autoscaled stateless pool.

## Role in the two-stage outbox (know the hop)
- Stage 1: `payment-service` writes an event to the LOCAL edge outbox (same tx as the state change).
- **Stage 2 (this module):** `LocalOutboxStoreAndForwardJob` polls the edge outbox → forwards rows to the central outbox.
- Stage 3 (elsewhere): `payment-central-relay` reads the central outbox → publishes to Kafka.
- So this module is the **edge→central bridge**. It moves rows; it does NOT deserialize payloads, mutate aggregates, or publish Kafka. Verified: no `KafkaTemplate` here.

## Inbound (driving) adapters = schedulers, not controllers (`infra/adapter/inbound/scheduler`)
- `LocalOutboxStoreAndForwardJob` — THE forwarder scheduler. `@Scheduled(initialDelay=30000, fixedDelay=500)` (polls ~every 500ms after a 30s warmup). Delegates the transactional work to `LocalOutboxDispatchWorker`; the job itself holds no logic.
- `LocalOutboxDispatchWorker` — the actual read-local → write-central transactional forwarding (`LocalOutboxStoreAndForwardPort` + `CentralOutboxForwarderPort`). `@WithSpan`-traced; also has a shutdown-flush path.
- `LocalOutboxMaintenanceJob` (file: `LocalOutboxMaintenceJob.kt` — note the misspelling) — edge-outbox **partition lifecycle** maintenance, separate from forwarding: `ensureCurrentAndNext` (pre-create partitions), `pruneOldPartitions`, `vacuumOldPartitionsWithNewRows`, each `@Scheduled` on its own cadence; emits `maintenanceErrorCounter` OTel metrics on failure. ⚠️ **Manual OTel context propagation lives here** — `val ctx = Context.current(); Runnable { ctx.makeCurrent().use { runnable.run() } }` — the canonical example of the root rule "manual OTel context propagation for background/outbox workers." Preserve this when adding async work.

## Outbound adapters + the multi-datasource fact
- `LocalOutboxStoreAndForwardAdapter : LocalOutboxStoreAndForwardPort` (reads edge-db) and `CentralOutboxForwarderAdapter : CentralOutboxForwarderPort` (writes central-db).
- **TWO datasources** — `MultiDataSourceConfig` wires an edge-db (read/claim) and a central-db (write) datasource; `MyBatisFactoriesConfig` binds each mapper to its datasource (`LocalOutboxMapperForEdgeWorker` → edge, `CentralOutboxForwarderMapper` → central); `DBWriterTxManager` manages the write-side tx. This dual-DB setup is unique to this module — don't assume a single default datasource.

## Config / runtime
- `config/PaymentEdgeWorkersThreadPoolConfig` — the dispatch worker pool.
- `POD_NAME=payment-edge-cell-<ordinal>` — the ordinal (parsed via `substringAfterLast("-")`) both identifies which edge cell this worker owns and derives the edge-db host `payment-edge-cell-<ordinal>.payment-edge-cell-headless`. Env supplies both edge + central DB creds (see `deploy-all-local.sh` / e2e `PlatformStack`).
- Image: `dcaglar1987/payment-edge-workers:latest`.

## Rules
- Never publish to Kafka, never deserialize payloads, never mutate domain aggregates — pure row-forwarder. It communicates by moving outbox rows (Separation of Powers).
- Schedulers stay thin; transactional work belongs in the dispatch worker/adapters.
- Background/async work must manually propagate OTel context (see `LocalOutboxMaintenanceJob`).
- One instance owns exactly one edge-db — don't add logic that assumes it sees multiple edge cells' outboxes.