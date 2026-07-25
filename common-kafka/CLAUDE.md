# common-kafka — shared Kafka adapter kernel (shared library, NOT deployable)

A shared **library**, not a runnable service — the Kafka sibling of `common-db`. Like `common-db` its only job is to provide reusable Kafka plumbing (publishing utilities, SerDe, topic/metadata catalogs) that is compiled INTO the actually-deployed modules; it is never deployed on its own. Same DDD posture: it sits on the infrastructure side, depends on the domain/`common` (for `EventEnvelope`/`Event`), and the arrow points inward — never the reverse.

**Wiring difference vs common-db:** this module has **no `@Configuration`/`@AutoConfiguration` of its own**. It exposes instantiable classes (`RawEventPublisher`, the serializers, `PaymentEventPublisher`); **each consuming/deployed module wires them as beans in its own Spring config**. So "provides the building blocks, the deployed module owns the wiring."

The Kafka **outbound/serde adapter** side of the hexagon. Interacts directly with the platform's Separation-of-Powers rules — read those in the root CLAUDE.md first.

## `RawEventPublisher` — the ONLY sanctioned publish path (GOLDEN RULE)
- `publishRaw(outboxEvent)` sends `outboxEvent.payload` **as already-serialized bytes** to `metadata.topic` (resolved via `EventMetaDataRegistry` from `eventType`), keyed by `outboxEvent.partitionKey`, with `parentEventId` as a header.
- **It does NOT deserialize.** Raw bytes in → Kafka. This is what lets `payment-central-relay` stay JSON-agnostic (root rule: "no deserialization in the relay; stream raw bytes"). Never add a `readValue` here.
- It's the routing "Enforcer" — topic comes from the metadata registry, not from call sites. Don't pass topics in by hand.
- **Only `OutboxRelayJob` drives this.** Consumers/services must never publish directly — they append to the outbox and let the relay publish (root "Separation of Powers").

## SerDe
- `EventEnvelopeKafkaSerializer` / `EventEnvelopeKafkaDeserializer` — envelope ⇄ bytes for the *consumer* side (where typed `EventEnvelope<T>` is actually needed). Preserve the concrete `T` (see common/CLAUDE.md). The relay path deliberately bypasses these (raw bytes).

## Catalogs (single source of truth)
- `Topics.kt` — the topic-name catalog. Reference these constants; never inline a topic string. ⚠️ Known drift: real capture topic is `gateway.capture.requested` (some docs say `.commands`).
- `PaymentEventMetadataCatalog` — the `eventType → (topic, payload class)` registrations backing `EventMetaDataRegistry`. New event type ⇒ register here + `Topics.kt`.
- `PaymentEventPublisher` / `KafkaDeliveryResult` — higher-level publish helper + delivery ack shape.

## Rule
- No Kafka transactions / exactly-once (root rule). Durability comes from the two-stage outbox, not Kafka EOS. Don't introduce `transactional.id` / `sendOffsetsToTransaction`.
