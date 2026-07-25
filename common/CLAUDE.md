# common — cross-cutting kernel

Framework-light shared kernel used by EVERY service. In hexagonal terms this is *shared domain-adjacent plumbing*, not an adapter and not the domain — it depends on `payment-domain` but on no infrastructure. Keep it that way (Jackson is allowed here because `EventEnvelope` is the wire contract; Spring/Kafka/DB are NOT).

## `EventEnvelope<T : Event>` — the wire contract (GOLDEN RULE)
- `data class EventEnvelope<T : Event>(eventId, eventType, aggregateId, data: T, timestamp, parentEventId?)`. Jackson `@JsonCreator`.
- **Preserve the concrete generic type.** Always carry `EventEnvelope<ConcretePayload>`, never widen to `EventEnvelope<*>` / `EventEnvelope<Event>` in app code — type erasure there defeats typed handling downstream. (Root CLAUDE.md "Type Preservation".)
- Build ONLY via `EventEnvelopeFactory` (`envelopeFor(...)` = caller-supplied id / traceId propagation; `envelopeWithRandomId(...)`). Don't hand-construct envelopes.
- `parentEventId` is the causal chain link (trace propagation across outbox hops) — preserve it when deriving a new event from a consumed one.

## Event metadata registry
- `EventMetaDataRegistry` / `EventMetadata` map an `eventType` string → routing (topic, payload class). It's the single source that `RawEventPublisher` (common-kafka) and deserializers consult. New event type ⇒ register here, don't scatter topic strings.

## Public IDs — `PublicIdFactory` / `PublicIdCodec`
- Internal ids are `Long`; external/public ids are prefixed, encoded strings: `pay_`, `pi_` (PaymentIntent), `tr_` (transfer), `le_` (ledger entry).
- Encode with `PublicIdFactory.public…Id(long)`, decode with `toInternalId(publicId)`. Never expose the raw `Long` over an API boundary; never parse a public id by hand — round-trip through the codec.

## Time — `Utc`
- All timestamps go through `Utc.nowInstant()` / `Utc.nowLocalDateTime()` / `Utc.fromInstant(...)`. Never `Instant.now()` / `LocalDateTime.now()` directly anywhere in the platform — `Utc` pins the zone. (This is why domain transitions take a `now` parameter.)

## Logging
- `EventLogContext` + `GenericLogFields` standardize MDC keys (eventId, traceId, aggregateId) for structured logs. Use these keys; don't invent per-service log field names.

## Testing
- Real unit tests exist here (`EventEnvelopeSerializationTest`, `EventMetadataRegistryTest`, …). If you touch the envelope shape or the metadata registry, these are your guardrails — run `mvn -pl common test`.
