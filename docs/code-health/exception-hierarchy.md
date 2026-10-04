# Exception hierarchy: one standard for the whole platform (2026-10-05)

**Goal: no generic exceptions.** Every exception we throw on purpose has a specific type, and its family says by
itself whether retrying can help. Nobody catches `Exception`; where a caller reacts differently per subtype it catches
the family once and branches with an exhaustive `when`; tests assert the exact type. Implements root `CLAUDE.md` §5
(classify by meaning: done? / no-won't / no-but-might / don't-know / our fault).

Why: before, the same failure was treated differently in different places. Retry was decided by three separate lists
(controller advice, Kafka error handler, application services) and, for domain rules, by whether someone wrote
`require` (`IllegalArgumentException`, not retried by Kafka) or `check` (`IllegalStateException`, retried).
See [step-4-error-handling-xray.md](step-4-error-handling-xray.md).

## The two layers

How a request flows decides who is at fault:

```
POST /payments          1. DTO validation (Bean Validation)          ← client errors are caught HERE
                        2. PaymentValidator                           ← and HERE
                        3. PaymentIntent.createNew                    (rules repeat step 2)
   … PSP authorizes … PaymentAuthorized event (Kafka)
                        4. Payment.initializeFromAuthEvent → init     (the only way to create a Payment;
                                                                       data already checked twice, from our own event)
```

- **The edge** (steps 1–2): a failure means **the caller is wrong** → `RequestValidationException` (400) or Bean
  Validation's own 400, never retried.
- **Inside the domain** (aggregate invariants, value objects, status transitions, the ledger): the data came from our
  own validated request or our own event, so a failure means **we are wrong** (a bug, or corrupt data: `init` also runs
  on rehydrate) → `PaymentDomainException`: 500 at the API, DLQ in Kafka, never retried.
  This includes `Amount` and `Currency`: client input reaches them only after the DTO validation (`@Min(1)` on the
  amount, the currency is an enum), so for a client they can't fail.
- Authorize looks at the intent's state **before** any transition (unknown id → 404, `CREATED_PENDING` → 409, a final
  or pending state → answered as it is), so a transition guard can't be triggered by a client either.

## The hierarchy

All in `payment-domain` (`domain/exception`, plain Kotlin, no framework).

```
RuntimeException
└── PaymentPlatformException (abstract)                  everything we throw on purpose
    ├── RetryableException (abstract)                     not done, may work later: retry
    │   ├── PspTransientException            ┐ also ExternalPspException (interface): every PSP failure
    │   ├── PspUnknownException              ┘ carries operation (PspOperation) + paymentIntentId
    │   ├── IdempotencyKeyInProgressException
    │   └── PaymentNotReadyException
    └── NonRetryableException (abstract)                  won't work on retry
        ├── PspPermanentException            ─ also ExternalPspException
        ├── IdempotencyKeyReusedException
        ├── PaymentIntentNotFoundException   (and the 404s of the back office)
        ├── RequestValidationException (sealed)           the caller is wrong → 400
        │   ├── PspInvalidPaymentException                payment method can't be sent to the PSP
        │   └── … one subtype per kind of input rule (phase 2: accounts, PaymentValidator)
        ├── PaymentDomainException (sealed)               a domain rule was violated: our bug → 500 / DLQ
        │   ├── InvariantViolationException                        a required value missing / wrong (merchantAccount blank,
        │   │                                             totalAmount ≤ 0, pspReference rules, no UNMATCHED capture)
        │   ├── InvalidStateTransitionException                    the state machine doesn't allow this status change
        │   ├── CaptureLimitExceededException             capture would exceed the total
        │   ├── RefundLimitExceededException              refund would exceed what was captured (or nothing was)
        │   ├── InvalidCaptureAmountException             capture amount ≤ 0 / captured amount negative
        │   ├── InvalidRefundAmountException              refund amount ≤ 0 / refunded amount negative
        │   ├── CurrencyMismatchException                 two amounts in different currencies
        │   ├── InvalidAmountException                    Amount.of: quantity must be > 0
        │   ├── InvalidCurrencyException                  Currency: not 3 capital letters
        │   └── SplitValidationException                  splits missing / mixed currencies / don't sum to the total
        └── LedgerException (sealed)                      (phase 4) the ledger code is wrong: page someone
            ├── UnbalancedJournalEntryException
            ├── LessThanTwoPostingsInJournalException
            └── DuplicateAccountInJournalException
```

- `ExternalPspException` is an **interface** (Kotlin has one parent class; the parent carries the retry meaning). It
  guarantees that every PSP failure says **which operation** (`PspOperation`: CREATE_INTENT, AUTHORIZE,
  RETRIEVE_CLIENT_SECRET, CAPTURE, REFUND) failed **for which payment**, and gives every message the same start
  (`PSP AUTHORIZE for paymentIntentId=…: …`).
- `PaymentPlatformException`, `RetryableException` and `NonRetryableException` are `abstract`, not `sealed`: modules
  other than `payment-domain` (e.g. the back office's not-found exceptions in payment-consumers) must be able to extend
  them. The families below them are `sealed`.
- `PaymentDomainException`'s subtypes are nested classes (`PaymentDomainException.InvalidStateTransitionException`), so the
  family is visible at every throw and catch site. It replaces the per-rule types planned earlier
  (`InvalidTransitionException`, `CurrencyMismatchException`, `MaxAmountExceeded…`, `PaymentInvariantException`,
  `PaymentIntentInvariantException`, `NonPositiveAmountException`).

## Throwing: `require` with our own exception (`domain/model/common/Preconditions.kt`)

Kotlin's `require` / `requireNotNull` always throw `IllegalArgumentException`. `Preconditions.kt` overloads them to
throw the exception the lambda returns, with a contract so smart casts still work:

```kotlin
require(capturedAmount <= totalAmount) {
    PaymentDomainException.CaptureLimitExceededException("paymentId=${paymentId.value}: capturedAmount … exceeds …")
}
val target = requireNotNull(allCaptures.find { … }) { PaymentDomainException.InvariantViolationException("…") }
```

- **Import them explicitly** (`import com.dogancaglar.paymentservice.domain.model.common.require`) everywhere outside
  the `model.common` package: without the import, Kotlin's own `require` is picked silently (its lambda type `() -> Any`
  accepts an exception object), and an `IllegalArgumentException` is thrown instead of ours. Inside `model.common`
  (e.g. `Amount.kt`) ours wins automatically.
- Every message names the payment it's about (`paymentId=…` / `paymentIntentId=…`): a failure must say which payment.

## Catching: one catch per family, an exhaustive `when` grouped by outcome

Only where the caller does something different per subtype (§5). Subtypes with the same outcome share a branch:

```kotlin
} catch (e: PaymentPlatformException) {
    when (e) {
        is PspPermanentException, is PspInvalidPaymentException -> return markFailed(intent, e)
        is PspTransientException -> { revertToCreated(intent); throw e }    // not done: authorizing again is safe
        is PspUnknownException -> return intent                              // maybe done: stays PENDING_AUTH (202)
        else -> throw e                                                      // not a PSP answer: propagates
    }
}
```

Over a sealed family (e.g. `PaymentDomainException`) there is **no `else`**: a new subtype doesn't compile until every
caller decides. detekt's `InstanceOfCheckForException` flags exactly this pattern, so it is switched off in
`config/detekt/detekt.yml`.

If every subtype would only propagate, don't catch at all: e.g. every `PaymentDomainException` in a Kafka consumer goes
to the DLQ by itself (below).

## What each family means to its handlers

| Family | API (controller advice) | Kafka error handler |
|---|---|---|
| `RetryableException` | 409 or 503 + `Retry-After` (status per type) | retried |
| `RequestValidationException` | 400 | not retried |
| `PaymentIntentNotFoundException` (and other not-found) | 404 | not retried |
| `IdempotencyKeyReusedException` | 422 | not retried |
| `PaymentDomainException`, `LedgerException`, `PspPermanentException` | 500 + logged as error | not retried → DLQ with the error class, message and stack trace as headers |

The Kafka error handler needs one line for all of them: `addNotRetryableExceptions(NonRetryableException::class.java)`
(`KafkaTypedConsumerFactoryConfig`).

## Which rule throws what

| Rules | Thrown by | Exception | Status |
|---|---|---|---|
| all `init` rules, transitions, captures, refunds, reconciliation | `Payment` | `PaymentDomainException` subtypes | **done** |
| all `init` / `createNew` rules, transitions, `pspReferenceOrThrow` | `PaymentIntent` | `PaymentDomainException` subtypes | **done** |
| quantity ≤ 0, currency format, currency mismatch | `Amount`, `Currency` | `InvalidAmountException`, `InvalidCurrencyException`, `CurrencyMismatchException` | **done** |
| transitions | `InternalTransfer`, `Tx.CaptureTx`, `OutboxEvent` | `PaymentDomainException.InvalidStateTransitionException` | phase 3 |
| account fields (blank names / address, country, codes, fee bps / currency) | `MerchantAccount`, `SellerAccount`, `Address`, `PlatformFee` (built from `POST /accounts`) | `RequestValidationException` subtypes | phase 2 |
| processing model / splits / totals / currencies of a payment request | `PaymentValidator` | `RequestValidationException` subtypes | phase 2 |
| journal entry unbalanced / < 2 postings / duplicate accounts; ledger account structure | `JournalEntry`, `LedgerAccount` | `LedgerException` subtypes | phase 4 |

**Stays Kotlin's `require` / `check` / `error`:** only true "can't happen" checks in technical code (e.g.
`PaymentTxEntityMapper`'s unknown tx type, a simulator's missing scenario).

## Phases (each one tested: unit + integration + e2e)

1. **The hierarchy**: `PaymentPlatformException` → `RetryableException` / `NonRetryableException`, today's exceptions
   re-parented, PSP exceptions with `operation` + `paymentIntentId`, Kafka error handler and controller advice by
   family. **Done 2026-10-05.**
1b. **`PaymentDomainException` + `Preconditions.kt`** for `Payment`, `PaymentIntent`, `Amount`, `Currency`; tests assert
   the exact subtype; authorize: `PspUnknownException` stays `PENDING_AUTH` (202) instead of going back to `CREATED`.
   **Done 2026-10-05** (389 tests + e2e green).
2. **`RequestValidationException`**: account objects, `PaymentValidator`.
3. **Remaining transitions**: `InternalTransfer`, `Tx.CaptureTx`, `OutboxEvent`.
4. **`LedgerException`** family.
5. **Callers**: remove the remaining `catch (e: Exception)` and the consumers' log-and-rethrow (step-4 X-ray; log once in
   the Kafka recoverer).

## Open

- The not-found exceptions (`PaymentIntentNotFoundException`, `TransactionNotFoundException`, `TxNotFoundException`,
  `BalanceOwnerNotFoundException`): keep as they are, or merge into one?
- `RequestValidationException` subtypes for the account rules (phase 2).
