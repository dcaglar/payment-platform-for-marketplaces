# Payment Authorization — architecture views

> **Scope:** one use case — `POST /api/v1/payments/{paymentIntentId}/authorize`.
> **Grounding:** written against source (`AuthorizePaymentIntentService.kt`, `PaymentIntent.kt`),
> not prose docs. Verify against the executable spec `e2e-tests/.../PaymentFlowE2EIntegrationTest.kt` (M0–M13).

This document uses the **C4 model** for structure (levels 3 and 4) and **UML behavioural
diagrams** for logic (state machine + sequence). Structure and behaviour are deliberately
separate diagrams — see [Notation](#notation) at the bottom.

---

## Level 3 — Component (inside `payment-service`, an Edge Cell)

*Question answered: which building blocks exist inside the edge container, and how do they collaborate?*

```mermaid
C4Component
    title Level 3 (Component) - inside payment-service, the Edge Cell

    Person(shopper, "Shopper", "Authorizes a checkout")

    Container_Boundary(edge, "payment-service :: Edge Cell pod") {
        Component(ctrl, "PaymentController", "Spring REST - inbound adapter", "POST /api/v1/payments/{id}/authorize - JWT payment:write")
        Component(orch, "PaymentApiOrchestrator", "inbound adapter", "maps DTO to AuthorizePaymentIntentCommand")
        Component(uc, "AuthorizePaymentIntentService", "USE CASE - framework-clean", "idempotency gate + concurrency gate + PSP orchestration")
        Component(agg, "PaymentIntent", "DOMAIN aggregate root", "guarded state machine, invariants via require()")
        Component(resil, "ResilientExecutionAdapter", "outbound adapter", "impl ResilientExecutionPort - 3s timeout + background fallback")
        Component(pspa, "PspAuthorizationGatewayAdapter", "outbound adapter", "impl PspAuthorizationGatewayPort - Stripe or Simulated")
        Component(repo, "PaymentIntentRepositoryAdapter", "MyBatis outbound adapter", "findById - tryMarkPendingAuth (atomic CAS)")
        Component(facade, "PaymentTransactionalFacadeAdapter", "outbound adapter - Transactional(2s)", "ONE tx: update intent + append outbox")
    }

    ContainerDb(edgedb, "edge-db", "PostgreSQL", "payment_intent + outbox_event (LOCAL)")
    System_Ext(psp, "PSP / Acquirer", "Stripe")

    Rel(shopper, ctrl, "POST /authorize", "HTTPS")
    Rel(ctrl, orch, "authorizePayment(dto)")
    Rel(orch, uc, "authorize(command)")
    Rel(uc, repo, "findById / tryMarkPendingAuth")
    Rel(uc, agg, "markAuthorizedPending / markAuthorized / markDeclined")
    Rel(uc, resil, "executeWithTimeoutAndBackgroundFallback")
    Rel(resil, pspa, "primaryTask")
    Rel(pspa, psp, "authorize", "HTTPS")
    Rel(uc, facade, "handleAuthorized(intent, outboxEvent)")
    Rel(repo, edgedb, "reads / writes", "JDBC")
    Rel(facade, edgedb, "atomic write", "JDBC")
```

**Reading the hexagon:** `AuthorizePaymentIntentService` depends only on *port interfaces*
(defined in `payment-application/ports`). Everything drawn as an "adapter" is a
concretion living in `payment-service`/`payment-infrastructure`. Dependencies point inward only.

---

## Level 4 — Code

*Question answered: what are the actual types and signatures?*

> C4's own guidance: draw level 4 rarely, and only for the parts that carry real design
> intent. Here that's the port interfaces and the guarded aggregate.

```mermaid
classDiagram
    direction LR

    class AuthorizePaymentIntentUseCase {
        <<interface>>
        +authorize(cmd) PaymentIntent
    }

    class AuthorizePaymentIntentService {
        -paymentIntentRepository
        -pspAuthGatewayPort
        -resilientExecutionPort
        -paymentTransactionalFacadePort
        -outboxEventFactoryPort
        +authorize(cmd) PaymentIntent
        -handleAuthorizedPaymentResult(intent)
        -handleBackgroundFailure(intent, error)
        -handleImmediateFailure(intent, error)
    }

    class PaymentIntent {
        +paymentIntentId : PaymentIntentId
        +pspReference : String
        +status : PaymentIntentStatus
        +totalAmount : Amount
        +markAuthorizedPending(now) PaymentIntent
        +markAuthorized(now) PaymentIntent
        +markDeclined(now) PaymentIntent
        +markCancelled(now) PaymentIntent
        +createNew()$ PaymentIntent
        +rehydrate()$ PaymentIntent
    }

    class PaymentIntentStatus {
        <<enumeration>>
        CREATED_PENDING
        CREATED
        PENDING_AUTH
        AUTHORIZED
        DECLINED
        CANCELLED
    }

    class PaymentIntentRepository {
        <<interface>>
        +findById(id) PaymentIntent
        +tryMarkPendingAuth(id, now) Boolean
        +updatePaymentIntent(intent)
    }

    class ResilientExecutionPort {
        <<interface>>
        +executeWithTimeoutAndBackgroundFallback(task, timeoutMs, onTimeout, onBgSuccess, onBgFailure)
    }

    class PspAuthorizationGatewayPort {
        <<interface>>
        +authorizePaymentIntent(intent, paymentMethod) PaymentIntent
    }

    class PaymentTransactionalFacadePort {
        <<interface>>
        +handleAuthorized(intent, outboxEvent)
    }

    AuthorizePaymentIntentService ..|> AuthorizePaymentIntentUseCase
    AuthorizePaymentIntentService --> PaymentIntentRepository
    AuthorizePaymentIntentService --> ResilientExecutionPort
    AuthorizePaymentIntentService --> PspAuthorizationGatewayPort
    AuthorizePaymentIntentService --> PaymentTransactionalFacadePort
    AuthorizePaymentIntentService --> PaymentIntent
    PaymentIntent --> PaymentIntentStatus
```

**Design intent visible here:** `PaymentIntent` has a `private constructor` + static
`createNew`/`rehydrate` factories, and every transition returns a *new* instance via a
private `copy(...)`. The aggregate is immutable and always-valid-by-construction.

---

## The logic (1) — state machine

*Question answered: what transitions are legal, and what enforces them?*

Every `markX()` method opens with `require(status == <legal source>)`. The state machine
is therefore **enforced by the domain**, not merely documented.

```mermaid
stateDiagram-v2
    direction LR

    [*] --> CREATED_PENDING : createNew()

    CREATED_PENDING --> CREATED : markAsCreatedWithPspReferenceAndClientSecret()

    CREATED --> PENDING_AUTH : tryMarkPendingAuth() - ATOMIC DB CAS
    CREATED --> CANCELLED : markCancelled()

    PENDING_AUTH --> AUTHORIZED : markAuthorized() - PSP approved
    PENDING_AUTH --> DECLINED : markDeclined() - PspPermanentException
    PENDING_AUTH --> CANCELLED : markCancelled()

    AUTHORIZED --> [*] : PaymentAuthorized event to outbox
    DECLINED --> [*]
    CANCELLED --> [*]

    note right of CREATED_PENDING
        pspReference MUST be null here.
        authorize() throws PaymentNotReadyException.
    end note

    note right of PENDING_AUTH
        Only ONE caller wins the CAS.
        Losers read current state back.
        Surfaces as HTTP 202 while PSP is slow.
    end note

    note right of AUTHORIZED
        Terminal for the intent.
        The real Payment aggregate is born
        later, centrally, from the event.
    end note
```

---

## The logic (2) — sequence, with every branch

*Question answered: what actually happens at runtime, including the failure paths?*

This is the diagram that carries the real complexity. The three gates — **idempotency**,
**concurrency**, **timeout** — are the whole design.

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant CTL as PaymentController
    participant UC as AuthorizePaymentIntentService
    participant REPO as PaymentIntentRepository
    participant PI as PaymentIntent
    participant RES as ResilientExecutionPort
    participant PSP as PSP Gateway
    participant TX as PaymentTransactionalFacade
    participant DB as edge-db

    C->>CTL: POST /payments/{id}/authorize
    CTL->>UC: authorize(cmd)
    UC->>REPO: findById(id)
    REPO-->>UC: PaymentIntent(status)

    alt status == CREATED_PENDING
        UC-->>C: PaymentNotReadyException
    else status in AUTHORIZED / DECLINED / CANCELLED / PENDING_AUTH
        UC-->>C: return as-is (IDEMPOTENT, no side effects)
    else status == CREATED
        UC->>REPO: tryMarkPendingAuth(id, now)
        REPO->>DB: atomic CAS - CREATED to PENDING_AUTH
        DB-->>REPO: won = true / false

        alt lost the race (won == false)
            UC->>REPO: findById(id)
            UC-->>C: latest state (another caller owns it)
        else won the race
            UC->>PI: markAuthorizedPending()
            UC->>RES: executeWithTimeoutAndBackgroundFallback(3000ms)
            RES->>PSP: authorizePaymentIntent(intent, method)

            alt PSP approves within 3s
                PSP-->>RES: status = AUTHORIZED
                UC->>UC: PaymentAuthorized.from(intent)
                UC->>TX: handleAuthorized(intent, outboxEvent)
                TX->>DB: ONE tx - UPDATE intent + INSERT outbox row
                UC-->>C: 200 AUTHORIZED
            else PspPermanentException
                PSP--xRES: permanent decline
                UC->>PI: markDeclined()
                UC->>REPO: updatePaymentIntent()
                UC-->>C: 200 DECLINED
            else PspTransientException
                PSP--xRES: transient error
                UC-->>C: unchanged state (safe to retry)
            else timeout over 3s
                UC-->>C: 202 PENDING_AUTH
                Note over RES,PSP: request continues in background
                PSP-->>RES: late result
                alt onBackgroundSuccess
                    RES->>UC: handleAuthorizedPaymentResult()
                    UC->>TX: handleAuthorized(intent, outboxEvent)
                    TX->>DB: ONE tx - UPDATE + outbox
                else onBackgroundFailure
                    RES->>UC: handleBackgroundFailure()
                    UC->>REPO: updatePaymentIntent(DECLINED)
                end
            end
        end
    end
```

**The one line that matters most:** `TX->>DB: ONE tx - UPDATE intent + INSERT outbox row`.
State change and event emission commit together, so there is no dual-write window.
`payment-service` never publishes to Kafka — see
[outbox-two-stage-pattern.md](./outbox-two-stage-pattern.md) for what happens to that row next.

---

## Notation

| View | Notation | Why this one |
|---|---|---|
| Level 3 Component | C4 (Mermaid `C4Component`) | Structure at a fixed abstraction level; C4 forbids mixing levels |
| Level 4 Code | UML class diagram | Types and signatures; the only thing that shows the port interfaces |
| State machine | UML state diagram | Legal transitions — the business rules of the aggregate |
| Sequence | UML sequence diagram | Ordering, concurrency and failure branches over time |

**C4 shows structure. It cannot show logic.** Branches, races, timeouts and ordering are
behavioural, so they need behavioural notation. That is why this document pairs the two —
using C4 alone for a flow like this is the single most common misuse of the model.

Diagrams are committed as Mermaid text so they diff in review and render in
IntelliJ and GitHub without a build step.
