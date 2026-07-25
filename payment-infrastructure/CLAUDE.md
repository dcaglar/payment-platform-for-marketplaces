# payment-infrastructure — shared outbound-adapter library (NOT deployable)

A shared **library**, not a runnable service (no `main`). It's the **right side of the hexagon**: the concrete, technology-specific **outbound adapters** that implement the `ports.outbound.*` interfaces declared upstream in `payment-application`. The deployed services (`payment-service`, `payment-consumers`, `payment-edge-workers`, `payment-central-relay`) depend on it to get their Snowflake IDs, Redis caches, JSON serialization, resilience, and metrics wired in.

## DDD / hexagonal posture (same arrow as common-db/common-kafka)
- **Ports are defined by the application/domain; implementations live HERE.** Dependency points inward: this module depends on `payment-application` (for the port interfaces) + `payment-domain`; never the reverse.
- To add an outbound capability: **define the port upstream first** (`payment-application/ports/outbound`), then implement the adapter here. Don't invent capability from the adapter side and leak it up.
- Adapters are swappable by contract — e.g. `SnowflakeIdGeneratorAdapter` vs `RedisIdGeneratorPortAdapter` both satisfy id-generation ports; the app doesn't know which.

## Wiring (auto-configured library)
- `PaymentInfrastructureAutoConfig` (`@AutoConfiguration`) + `@Component`/`@Configuration` classes → consumers get every adapter as a bean just by depending on this module. They don't re-declare them.
- `JacksonConfig` exposes `@Bean("myObjectMapper")` — **the** configured `ObjectMapper` (Kotlin module, etc.). Inject that bean; never `ObjectMapper()` ad hoc. `RedisConfig` exposes the Redis template/connection beans.
- `IdGenerationProperties` is `@ConfigurationProperties(prefix = "payments.id")` — Snowflake node/worker config comes from there (typically derived from the pod ordinal); don't hardcode a worker id.

## The adapters (port → impl)
- **IDs**: `SnowflakeIdGeneratorAdapter` (+ `SnowflakeCore`) for time-ordered 64-bit ids; `RedisIdGeneratorPortAdapter : ExternalIdGeneratorPort` for a Redis-backed sequence. Snowflake ids are the platform's primary keys.
- **Serialization**: `JacksonSerializationAdapter : SerializationPort` — the app serializes/deserializes ONLY through this port (backed by `myObjectMapper`). `OutboxEventEventFactory` builds outbox payloads. `JacksonUtil` = static helpers.
- **Hashing/idempotency**: `CanonicalJsonHasher : HasherPort` — canonical (stable field order) JSON hashing so the same logical request always hashes identically (idempotency keys).
- **Redis caches/queues**: `AccountBalanceRedisCacheAdapter : AccountBalanceCachePort`, `RedisEventDedupAdapter : EventDeduplicationPort` (consumer-side dedup), `CaptureRetryQueueAdapter` (+ `CaptureRetryRedisCache`) for the capture retry queue.
- **Resilience**: `ResilientExecutionAdapter : ResilientExecutionPort` — the `executeWithTimeoutAndBackgroundFallback(...)` used by the authorize flow (primary task + timeout → 202 + background completion). Timeout/fallback policy lives here, not in the use case.

## Observability (root rule applies)
- `monitoring/` (`MetricNames`, `MetricHelper`, `JobMetrics`, `RedisMetrics`) — OpenTelemetry metric helpers only. **OTel Spring Boot starter, push model** — no Micrometer, no Java agent. Background/outbox workers need **manual OTel context propagation** (root CLAUDE.md). Add new metric names to `MetricNames`, don't inline strings.

## Rules
- Keep tech choices (Redis, Jackson, Snowflake) sealed behind their ports — no Redis/Jackson types in method signatures the application sees.
- Adapters are stateless technical plumbing; no business rules, no domain mutation here.
