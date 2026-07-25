# payment-domain — module rules

Pure Kotlin business core. The hexagon's center. Loaded on top of the root CLAUDE.md.

## DDD approach (this module IS the tactical domain model)
This is the **domain core of a textbook hexagonal (ports-and-adapters) architecture** — the innermost ring. It's framework-agnostic on purpose so that adapters (persistence, Kafka, REST, PSP) plug in *around* it via ports; nothing in here knows those adapters exist. If you understand hexagonal architecture, this module is exactly the shape you'd expect: pure model in the middle, dependencies pointing inward.

It models the **ubiquitous language** in code and nothing else. Respect the tactical patterns already in place:
- **Aggregate roots** = the consistency boundaries: `PaymentIntent`, `Payment`, `JournalEntry`, `InternalTransfer` (+ `OutboxEvent` as a technical aggregate). Each guards its own invariants in `init{}`/`createNew` and is the ONLY entry point for mutating its internals. Never reach past a root to mutate a child (`Posting`, `Tx`) directly.
- **Value objects** = identity-less, immutable, self-validating: `Amount`/`Currency`, `PaymentSplit`, and every `@JvmInline value class …Id`. Equality is by value. Prefer a VO over a primitive in every signature.
- **Rich model, NOT anemic**: behavior lives ON the aggregate (`markAuthorized`, `markAsCreated`, balance/sign logic), not in external services. If you're tempted to write a `…Service` that pulls data out of an aggregate to compute on it, the method belongs on the aggregate.
- **Invariants at the boundary**: business rules are enforced once, at construction/transition (`require(...)`), so an instance is *always valid by existence*. Callers never re-check.
- **Ubiquitous language is deliberate** — e.g. `PaymentIntent` (edge-only intent to pay, no money movement) vs `Payment` (central, real money) is a domain distinction, not a naming accident (architecture.md §4). Keep names aligned with the business, both ways.
- **State lives here; change does NOT.** The domain models **state** — an aggregate holds its current status (`PaymentIntent` in `AUTHORIZED`). An **event** like `PaymentAuthorized` is not a domain object: it represents a *change / something that happened* (past-tense fact of a transition), not a state. That conceptual difference is why events are NOT in this module — they live one layer out in `payment-application/events` (`PaymentAuthorized`, `CaptureRequested`, …), alongside use-case coordination and ports (except the one legacy outlier below). This module has NO domain services and NO event classes; keep orchestration and events OUT of here.

## Hard boundary (never break)
- **Zero framework deps.** No Spring, no MyBatis/ibatis, no Jackson, no JPA. Verified clean — keep it that way. If you reach for an annotation or a serializer here, it belongs in `payment-application` (ports) or `payment-infrastructure`/`common-db` (adapters), not here.
- No I/O, no clocks-from-thin-air: time comes in as a `LocalDateTime`/`Instant` parameter (see `Utc` usage), never `Instant.now()` inline in a transition.
- The one intentional outlier: `ports/outbound/TransferRepository.kt` — a port interface living in domain. Don't add more; new ports go in `payment-application/ports`.

## Aggregate construction convention (follow exactly)
Every aggregate (`PaymentIntent`, `Payment`, `JournalEntry`, `InternalTransfer`, `OutboxEvent`) uses:
- `private constructor(...)` — never called directly.
- `companion object.createNew(...)` — the ONLY place business invariants are enforced (`require(...)`). Use for genuinely new instances.
- `companion object.rehydrate(...)` — reconstructs from persisted state. **Trusts the DB**: minimal/no invariant checks (comment in JournalEntry: "assume the DB holds valid, balanced data"). Do NOT duplicate createNew's validation here.
- State transitions return a NEW instance via a private `copy(...)`; aggregates are effectively immutable. A transition `require(...)`s the legal source status first (state-machine guard).

## Money — `Amount` / `Currency`
- **Minor units only** (cents/pence). `€15.50` = `Amount.of(1550, Currency("EUR"))`. Never floats.
- Construct via `Amount.of(quantity, currency)` (enforces `quantity > 0`) or `Amount.zero(currency)`. Constructor is private.
- `plus`/`minus`/`compareTo` **require same currency** — cross-currency math throws. `Currency` is a `@JvmInline value class` validated to `^[A-Z]{3}$`.

## Double-entry ledger (`domain/model/ledger`)
- `JournalEntry.init{}` enforces the invariants — a change here ripples everywhere:
  - `postings.size >= 2`, **totalDebit quantity == totalCredit quantity** (balanced), positive global id, no duplicate postings.
- `Posting` is a sealed `Debit`/`Credit` (private ctors, `.create(account, amount)` factories). `getSignedAmount()` flips sign based on the account's normal side (`account.isDebitAccount()` / `isCreditAccount()`) — a debit to a credit-normal account is negative.
- `JournalType`: AUTHORIZATION, CAPTURE, INTERNAL_TRANSFER, REFUND, SETTLEMENT, PSP_FEE, COMMISSION_FEE, REVENUE_RECOGNITION, PAYOUT, ADJUSTMENT.

## Value objects
- IDs are `@JvmInline value class XId(val value: Long)` (`PaymentIntentId`, `PaymentId`, `TxId`, `BuyerId`, `SellerId`, `OrderId`, `InternalTransferId`). Pass these, not raw `Long`, across signatures.

## State machines (guard source status in every transition)
- `PaymentIntentStatus`: CREATED_PENDING → CREATED → PENDING_AUTH → AUTHORIZED | DECLINED | CANCELLED.
- `PaymentStatus`: AUTHORIZED → SENT_FOR_SETTLE → CAPTURED → SETTLED (also PARTIALLY_CAPTURED, VOIDED, PARTIALLY_REFUNDED, REFUNDED).

## `PaymentIntent.splits` — lazy delegate (know this before editing)
- Field is `val splits by splitsDelegate: Lazy<List<PaymentSplit>>`.
- `createNew` wraps an in-hand list with `lazyOf(splits)` (already resolved — write path is a no-op force).
- `rehydrate` takes a deferred `lazy { objectMapper.readValue(splitsJson) }` supplied by the persistence adapter, so loading an intent does NOT parse the splits JSON until `.splits` is first read.
- `copy()` forwards the SAME delegate, so a status transition never re-parses splits.
- Consequence: `splits_json` is write-once (at create/insert); auth and all other transitions persist via targeted UPDATEs that omit it. A corrupt (non-blank) `splits_json` throws lazily at first `.splits` access (e.g. `PaymentAuthorized.from`), not at load.

## Testing
- Pure unit tests, mocks only (`*Test.kt`, `mvn test`). No containers.
- ⚠️ Known-broken: `PaymentIntentTest.kt` uses an outdated `splits=` (now `splitsDelegate=`). It breaks `-am` reactor builds — run around it, don't "fix" it unless asked.
