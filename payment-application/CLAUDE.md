# payment-application — the use-case / application layer

The **application core of the hexagon**: it orchestrates domain aggregates + ports to fulfil business capabilities. It says **WHAT must happen, never HOW.** Take `CreatePaymentIntentService`: "generate an id, `createNew` a PaymentIntent, **save it**, **call the PSP** with a timeout, on timeout return pending, on background success update it." It does NOT know which database, which PSP, which thread pool, which serializer — every collaborator is an injected **port interface**. That indirection is the whole point of this layer.

## THE boundary rule (violating it breaks the architecture)
- **No Spring. No infrastructure anywhere in the services/use cases.** No `@Component`/`@Service`/`@Autowired`/`@Configuration`, no MyBatis, no Kafka, no Redis, no HTTP, no `ObjectMapper`. Verified: `service/` is 100% framework-clean. If you're about to add a framework annotation or an infra type to a service, STOP — that's a domain-boundary violation.
- Services are **plain classes with constructor-injected ports**. Beans are assembled in the *deployed* module's own `@Configuration` (e.g. `PaymentServiceConfig`), NOT here. This module is a library; it is not deployable and does no wiring.
- Dependencies point inward only: application → `payment-domain` + its own port interfaces. Never onto `payment-infrastructure`/`common-db`/`common-kafka` concretions.

## The hexagon's ports are DEFINED here (both directions)
- **Inbound (driving) ports = `ports/inbound/usecases/*`** — the capabilities this layer offers (`CreatePaymentIntentUseCase`, `AuthorizePaymentIntentUseCase`, `CapturePaymentUseCase`, `ExecuteCaptureUseCase`, `ProcessPspResultUseCase`, …). Implemented by the `service/*` classes here; *called* by inbound adapters (REST controllers, `@KafkaListener`s) that live in the deployed modules.
- **Outbound (driven) ports = `ports/outbound/*`** (~30 interfaces) — what the app needs the world to provide: `PaymentIntentRepository`, `PaymentRepository`, `PspAuthorizationGatewayPort`, `PspCaptureGatewayPort`, `LocalOutboxWriterPort`, `CentralOutboxWriterPort`, `SerializationPort`, `IdGeneratorPort`, `ResilientExecutionPort`, `EventDeduplicationPort`, `RetryQueuePort`, the transactional-facade ports, … Implemented by adapters in `payment-infrastructure` / `common-*` / the deployed modules.
- Add a capability = define the port here first, implement the adapter outward. Never the reverse.

## Services = orchestration only (`service/*`, ~12)
- Coordinate: load/`createNew` an aggregate → call a domain transition (`markAuthorized`, `markCancelled`) → persist via a repository port → append events to an outbox port. Business *rules* stay in the domain aggregate; services only sequence them.
- **Separation of powers shows up here**: services persist and **append to the outbox** (`LocalOutboxWriterPort`/`CentralOutboxWriterPort`); they NEVER publish to Kafka directly (only `OutboxRelayJob` does). No service imports a Kafka producer.
- Even cross-cutting mechanics are behind ports: the create/authorize timeout + background-completion is `ResilientExecutionPort`, not a raw `CompletableFuture`/executor in the service.

## Inputs & events
- `command/*` = use-case inputs (`CreatePaymentIntentCommand`, `AuthorizePaymentIntentCommand`, …). Controllers map HTTP → command; the use case takes the command, returns a domain object.
- `events/*` = **application/domain events** — past-tense facts of a *change* (`PaymentAuthorized`, `CaptureRequested`, `CaptureSubmitted`, `CaptureConfirmed`, `JournalEntriesRecorded`). This is where "change" lives (domain models *state*; events model *change* — see payment-domain/CLAUDE.md). Built via `…from(aggregate)` factories.
- ⚠️ **KNOWN BOUNDARY VIOLATION (tech debt — scheduled to fix):** `dto/PaymentSplitDto` (and the `events/*` payloads) carry **Jackson annotations** (`@JsonProperty`, `@JsonCreator`, `@JsonIgnoreProperties`) because they're serialized across the outbox/Kafka wire. Jackson is an infrastructure concern and does NOT belong in the application layer — this is a leak, not a sanctioned exception. `PaymentSplitDto` is the flagged instance to clean up first; the event payloads share the same smell and are candidates for the same treatment.
  - **Intended fix (decide at fix-time):** keep the application DTO/events as plain Kotlin data classes and move serialization out — either via Jackson **mixins registered in `JacksonConfig` (payment-infrastructure)**, or by relocating the wire DTOs to a serialization/adapter module. The wire contract (stable field names, deterministic order) must be preserved by whichever approach; only the *dependency direction* changes.
  - Until fixed: do NOT read this as license to annotate services or add more Jackson to this layer. Services stay 100% clean.

## Rules of thumb
- Writing a service and reaching for an infra type or `@Annotation`? The need belongs behind a port.
- New event? It's a fact about something that already happened (past tense), Jackson-annotated, built from an aggregate — not a command, not a domain object.
- Keep services thin: sequence + delegate. If logic decides a business invariant, it belongs on the aggregate.
