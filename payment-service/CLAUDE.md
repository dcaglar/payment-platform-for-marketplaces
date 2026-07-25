# payment-service — Edge REST API (DEPLOYABLE Spring Boot app)

The first **runnable** module: a Spring Boot app (`PaymentServiceApplication`, has a `Dockerfile`) that IS the Edge Cell's synchronous front door. It handles checkout (`createPayment` → `authorize`), talks to the PSP (Stripe or a local simulator), and writes to the **local edge-db**. Deployed co-located with edge-db as `payment-edge-cell`; NGINX Lua routes requests by Snowflake id.

## This module is the COMPOSITION ROOT (the key wiring fact)
- `config/PaymentServiceConfig` declares the framework-clean **use-case services from `payment-application` as `@Bean`s** — it hand-constructs `CreatePaymentIntentService`, `AuthorizePaymentIntentService`, `CapturePaymentService`, `UpdatePaymentIntentService`, `GetPaymentIntentService`, `IdempotencyService`, … passing each its ports (Spring resolves those to the adapter beans below).
- **This is WHY `payment-application` carries no Spring annotations**: all wiring lives HERE. The deployed module is the composition root that binds the clean core to concrete adapters. If you need a new use case wired, add a `@Bean` here — do NOT annotate the service in `payment-application`.
- `config/PaymentServiceThreadPoolConfig` owns the PSP executor thread pool that backs `ResilientExecutionPort`'s timeout + background-completion.

## Left side of the hexagon — inbound (driving) adapters (`adapter/inbound/rest`)
- `PaymentController` — the checkout API: `POST /api/v1/payments` (create) and `POST /api/v1/payments/{id}/authorize`. Thin: map HTTP → command → use case → HTTP. No business logic in controllers.
- `WebhookController`, `AdyenWebhookController` — inbound PSP webhooks.
- `rest/dto`, `rest/mapper` — HTTP request/response DTOs and HTTP⇄`command` mappers (controllers speak DTO/command, never touch domain aggregates directly).
- `rest/validation` — request validation (e.g. the UUIDv7 idempotency-key check).
- `rest/webconfig` — `SecurityConfig` (JWT/Keycloak resource-server; issuer `KEYCLOAK_ISSUER_URL`), `GlobalExceptionHandler` + `PaymentControllerWebExceptionHandler` (domain exceptions → HTTP codes; note PSP timeout → **202 Accepted**, not an error), `TraceFilter` (OTel trace context on inbound requests).

## Right side — this module's own outbound adapters
- **PSP** (`infra/adapter/outbound/psp`): two `@Component` impls of `PspAuthorizationGatewayPort`, selected by config:
  - `@ConditionalOnProperty("psp.gateway.type", havingValue="STRIPE", matchIfMissing=true)` → `StripePspAuthorizationGatewayAdapter` (**default**).
  - `havingValue="SIMULATED"` → `SimulatedPspAuthorizationGatewayAdapter` (+ `AuthorizationNetworkSimulator`).
  - ⚠️ Since STRIPE is `matchIfMissing=true`, **local/e2e MUST set `psp.gateway.type=SIMULATED`** or you'll hit real Stripe. The `MARKETPLACE-5` hardcoded sim target drives the auto-settle e2e path.
- **Persistence** (`infra/adapter/outbound/persistence`): edge-db adapters — `PaymentIntentOutboundAdapter` (the `save`/`findById`/partial-update we dissected + the splits lazy-delegate), `IdempotencyStoreAdapter`, `LocalOutboxWriterAdapter` (impl of `LocalOutboxWriterPort`), `PaymentTransactionalFacadeAdapter`. MyBatis mappers under `mapper/edge` (+ a `mapper/yugabyte` for the idempotency key).

## Edge-cell rules (separation of powers)
- **NEVER publish to Kafka from here.** Verified: no `KafkaTemplate`/producer in this module. The write path is: persist to edge-db + **append to the LOCAL outbox** via `LocalOutboxWriterPort`. `payment-edge-workers` forwards local→central; the central `OutboxRelayJob` is the only thing that publishes. Adding a producer here breaks the architecture.
- Services persist via repository ports and append events to the outbox — controllers/adapters never mutate aggregates.
- `infra/adapter/inbound/shutdown/EdgeApiGracefulShutdownHook` drains in-flight work before pod termination — respect it when touching lifecycle.

## Runtime / profiles
- `SPRING_PROFILES_ACTIVE=local` for the simulator-backed local/e2e stack; infra addresses (edge-db, redis, keycloak issuer) come from env (see `deploy-all-local.sh` / e2e `PlatformStack`). `POD_NAME` ordinal feeds the Snowflake worker id + edge-cell identity.
- Build/deploy: `dcaglar1987/payment-service:latest` (see `infra/scripts/build-all-payment-platform-images-and-push.sh`).

## Testing
- Unit: `mvn test`. Integration (`*IntegrationTest.kt`, `@Tag("integration")`): `mvn verify`, TestContainers. Full black-box flow lives in the separate `e2e-tests` module (not a reactor module).
