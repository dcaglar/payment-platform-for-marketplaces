# payment-consumers — async ledger processors (DEPLOYABLE)

Central-cluster Spring Boot app (`@KafkaListener`s). **The ONLY components allowed to mutate the central ledger.** Downstream comms = append to the outbox ONLY — never publish Kafka (verified: no producer here, only a consumer factory).

## Recurring consumer skeleton (all 5 follow it — match it)
Each `@Component` consumer is a THIN inbound adapter: no business logic, no direct ledger write. In order:
1. `EventLogContext.with(envelope) { }` wraps the body (eventId/traceId in logs+span).
2. Dedup guard: `if (dedupe.exists(envelope.data.deterministicEventId())) return`.
3. Delegate to an inbound use-case port — the ONLY place work happens.
4. `dedupe.markProcessed(eventId, 3600)` AFTER success.
5. `catch → throw` (never swallow; Kafka owns retry/DLQ).

Invariants: topic/group/factory come from `Topics.*` + `CONSUMER_GROUPS.*` (common-kafka), never inline strings. Payload is a typed `EventEnvelope<T>`. Dedup on `deterministicEventId()` (data-derived, e.g. `"$publicPaymentIntentId:$eventType[:$attempt]"`), NOT the random `envelope.eventId`.

## Idempotency (two layers — at-least
-once is safe because of this)
- Fast: Redis `EventDeduplicationPort` (impl `RedisEventDedupAdapter`, DI-wired), TTL 1h — not durable alone.
- Durable: journal-entry ids are deterministic; ledger insert **skips on duplicate id** (`CentralDbTransactionalFacadeAdapter.saveJournalAndOutbox`). Redis is the optimization; the DB is the guarantee. Don't add a ledger path that bypasses skip-on-duplicate.

## Write path
- `CentralDbTransactionalFacadeAdapter` (`@Transactional(timeout=5)`) writes journal entries + postings **and** the next outbox events in ONE tx — write-side transactional outbox. Downstream event append = `CentralOutboxWriterAdapter`. `LedgerMapper`/`LedgerEntitiyMapper` persist `JournalEntry`+`Posting`. Single central-db datasource.

## Consumer roster (topic → role)
- `PspResultConsumer` ← `PSP_RESULTS` — multiplexer: `when(event)` routes PaymentAuthorized/CaptureConfirmed/InternalTransferCommand/SettlementReceived → `ProcessPspResultUseCase` (records AUTHORIZATION/CAPTURE/SETTLEMENT).
- `CaptureCommandExecutor` ← `CAPTURE_REQUESTED` — `ExecuteCaptureUseCase` (PSP capture call → outbox).
- `AccountBalanceConsumer` ← `JOURNAL_ENTRIES_RECORDED` — projects entries into balance snapshots/cache.
- `GrossCaptureAllocationConsumer` ← `JOURNAL_ENTRIES_RECORDED` — `RecordInternalTransferSubmissionUseCase` (gross→seller/commission allocation).
- `CapturePspPerformedConsumer` ← `CAPTURE_SUBMITTED_ACKS`.
- ⚠️ Same topic (`JOURNAL_ENTRIES_RECORDED`), different `groupId`s = deliberate independent fan-out. Don't consolidate.

## Other infra
`KafkaTypedConsumerFactoryConfig` (per-group typed `<group>-factory` — register new groups here), `psp/*` capture/settlement adapters, `snapshotmapper`, capture-retry `redis/client` + retry `scheduler`, small `adapter/inbound/rest` (balance-read/ops).