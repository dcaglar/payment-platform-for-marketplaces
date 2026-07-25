# payment-central-relay — the ONLY Kafka publisher (DEPLOYABLE, background-only)

Standalone Spring Boot app (`PaymentCentralRelayApplication`, has a `Dockerfile`) in the **Central Cluster** (anti-affinity-separated from `payment-consumers`). NO REST — `@Scheduled` jobs only. It implements **stage 3 of the two-stage outbox**: read the central-db outbox and publish to Kafka. It is the **single component in the whole platform allowed to publish to Kafka** (root Separation of Powers).

## What it does / does NOT do
- **Publishes pre-serialized RAW bytes.** It streams `outboxEvent.payload` straight to Kafka via `RawEventPublisher.publishRaw` (in `common-kafka`) — it NEVER deserializes the payload, never needs the payload classes (root "Type Preservation / no deser in the relay"). Don't add a `readValue`/`ObjectMapper` to this module.
- **It routes; it does not mutate domain/ledger state.** The only writes it makes are outbox *dispatch bookkeeping* (claim → dispatched → unclaim/reclaim). No `Payment`/`JournalEntry` mutation ever.

## T_Safe — the ordering/completeness gate (the defining concept)
- Before each batch, `CentralOutboxDispatchWorker` computes `T_safe = centralOutboxRepository.computeTSafe()`, defined in SQL as **`SELECT MIN(forwarded_up_to) FROM edge_watermarks`** — the earliest point that EVERY edge forwarder (`payment-edge-workers`) has confirmed forwarding up to.
- It then `findEligible(tSafe, batchSize, workerId)` — only publishes central-outbox rows **at/below T_safe**. This guarantees no event from a *lagging* edge cell is still in flight below the cutoff, preserving global temporal completeness across all edge cells. This is what "polls behind T_Safe" means.
- Each edge-workers instance advances its own `edge_watermarks.forwarded_up_to`; the relay is gated by the SLOWEST one. A stuck edge forwarder therefore stalls the relay by design — that's the safety tradeoff, not a bug.

## Publish flow + ordering + at-least-once (`infra/adapter/inbound/scheduler`)
- `OutboxRelayJob` (`@Service`) — THE relay scheduler. `poll()` `@Scheduled(initialDelay=15s, fixedDelay=5s)` → `dispatchWorker.centralOutboxRelayBatchWorker()`. `reclaimStuck()` `@Scheduled(initialDelay=60s, fixedDelay=120s)`.
- `CentralOutboxDispatchWorker.centralOutboxRelayBatchWorker()` — compute T_safe → claim a batch → **group by `aggregateId`** → per aggregate, publish entries **in order**; on any failure **break the chain for that aggregate** and `unclaimSpecific` (never publish a later event for an aggregate past a failed earlier one — per-aggregate ordering is sacred). `markDispatched` on success.
- `reclaimStuck` — rows stuck in PROCESSING > 120s (crashed worker) revert NEW → re-published. So delivery is **at-least-once**. NOTE: this module does NO dedup — coping with redelivery is the DOWNSTREAM consumers' responsibility (`payment-consumers`), not the relay's. The relay just publishes; keep dedup concerns out of here.
- `CentralOutboxKafkaHelper` — thin `@WithSpan("publish-outbox-event")` wrapper over `rawEventPublisher.publishRaw(entry)`.
- `RetryDispatcherScheduler` — handles retry/inflight bookkeeping (`@Scheduled` 5s/30s).
- `CentralOutboxMaintenanceJob` — central-outbox partition lifecycle (ensure/prune/vacuum), sibling of the edge one.

## Wiring / infra
- `infra/adapter/outbound/persistence`: `CentralOutboxRelayAdapter : CentralOutboxRelayPort` (computeTSafe / findEligible / markDispatched / unclaimSpecific / reclaimStuck — the claim state machine) + MyBatis `CentralOutboxRelayMapper`(.xml). `CentralOutboxDataSourceConfig` — a SINGLE central-db datasource (contrast edge-workers' dual-DB).
- `infra/adapter/outbound/kafka/KafkaProducerConfig` — builds the `KafkaTemplate` + the `RawEventPublisher` bean with "safe reliability knobs" (acks=all, idempotent producer). ⚠️ **No Kafka transactions / EOS** (root rule) — durability comes from the outbox + at-least-once + consumer dedup, NOT from `transactional.id`.
- `config/CentralOutboxRelayJobThreadPoolConfig` — the relay executor pool. `POD_NAME=payment-central-relay-<ordinal>` feeds the worker/app-instance id used in claim ownership.
- Image: `dcaglar1987/payment-central-relay:latest`.

## Rules
- This is the ONLY publisher — never add a Kafka producer anywhere else; never make the relay do anything but route outbox rows.
- Never deserialize the payload here; raw bytes only.
- Preserve per-aggregate ordering (break-chain-on-failure) and the T_safe gate — both are correctness-critical, not optimizations.
- Never mutate ledger/operational state; only outbox dispatch columns.