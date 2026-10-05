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

All in `payment-domain` (`domain/exception`, plain Kotlin, no framework). Every `*DomainException` is non-retryable
(→ 500 at the API, DLQ in Kafka), except where an API handler maps a client-input subtype to 400 (accounts).

```
RuntimeException
└── PaymentPlatformException (abstract)
    ├── RetryableException (abstract)
    │   ├── PspTransientException, PspUnknownException     (also ExternalPspException: operation + paymentIntentId)
    │   ├── IdempotencyKeyInProgressException
    │   └── PaymentNotReadyException
    └── NonRetryableException (abstract)
        ├── PspPermanentException                           (also ExternalPspException)
        ├── IdempotencyKeyReusedException
        ├── RequestValidationException (sealed)             → PspInvalidPaymentException
        ├── PaymentDomainException (sealed)                 Payment, Amount, Currency, PaymentSplit, CardSummary,
        │       InvariantViolationException, InvalidStateTransitionException,   Tx (capture reconciliation)
        │       CaptureLimitExceededException, RefundLimitExceededException,
        │       InvalidCaptureAmountException, InvalidRefundAmountException, CurrencyMismatchException,
        │       InvalidAmountException, InvalidCurrencyException, SplitValidationException,
        │       PaymentNotFoundException, CaptureTxNotFoundException
        ├── PaymentIntentDomainException (sealed)           PaymentIntent
        │       PaymentIntentNotFoundException (→ 404), InvariantViolationException,
        │       InvalidStateTransitionException, SplitValidationException, SplitCurrencyMismatchException
        ├── InternalTransferDomainException (sealed)        InternalTransfer
        │       TransferNotFoundException, InvariantViolationException, InvalidStateTransitionException
        ├── AccountDomainException (sealed)                 MerchantAccount, Address, PlatformFee, SellerAccount
        │       MerchantAccountNotFoundException, SellerAccountNotFoundException (→ 404 in the balance API),
        │       InvariantViolationException (→ 400 in the account API)
        ├── LedgerDomainException (sealed)                  JournalEntry, LedgerAccount
        │       UnbalancedJournalEntryException, LessThanTwoPostingsInJournalException,
        │       DuplicateAccountInJournalException, InvariantViolationException,
        │       TxNotFoundException (→ 404 in the tx API)
        └── OutboxEventDomainException (sealed)             OutboxEvent
                InvalidStateTransitionException
```

Every `require` in `payment-domain` (87) goes through `Preconditions.kt` and throws one of these. Each file imports our
`require`, so a rule passing a plain message no longer compiles.

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
| `PaymentIntentDomainException.PaymentIntentNotFoundException` (and other not-found) | 404 | not retried |
| `IdempotencyKeyReusedException` | 422 | not retried |
| `PaymentDomainException`, `LedgerException`, `PspPermanentException` | 500 + logged as error | not retried → DLQ with the error class, message and stack trace as headers |

The Kafka error handler needs one line for all of them: `addNotRetryableExceptions(NonRetryableException::class.java)`
(`KafkaTypedConsumerFactoryConfig`).

## Which rule throws what

| Rules | Thrown by | Exception | Status |
|---|---|---|---|
| all `init` rules, transitions, captures, refunds, reconciliation | `Payment` | `PaymentDomainException` subtypes | **done** |
| all `init` / `createNew` rules, transitions, `pspReferenceOrThrow` | `PaymentIntent` | `PaymentIntentDomainException` subtypes | **done** |
| quantity ≤ 0, currency format, currency mismatch | `Amount`, `Currency` | `InvalidAmountException`, `InvalidCurrencyException`, `CurrencyMismatchException` | **done** |
| transitions | `InternalTransfer`, `Tx.CaptureTx`, `OutboxEvent` | `PaymentDomainException.InvalidStateTransitionException` | phase 3 |
| account fields (blank names / address, country, codes, fee bps / currency) | `MerchantAccount`, `SellerAccount`, `Address`, `PlatformFee` (built from `POST /accounts`) | `RequestValidationException` subtypes | phase 2 |
| processing model / splits / totals / currencies of a payment request | `PaymentValidator` | `RequestValidationException` subtypes | phase 2 |
| journal entry unbalanced / < 2 postings / duplicate accounts; ledger account structure | `JournalEntry`, `LedgerAccount` | `LedgerException` subtypes | phase 4 |

**Stays Kotlin's `require` / `check` / `error`:** only true "can't happen" checks in technical code (e.g.
`PaymentTxEntityMapper`'s unknown tx type, a simulator's missing scenario).

## Status

- **Done (2026-10-05):** the hierarchy; PSP exceptions; every domain rule in `payment-domain` through `Preconditions.kt`
  and the families above; "not found" lookups in the services (payment, capture tx, transfer, merchant) as domain
  exceptions (non-retryable → DLQ, no longer retried 5×); no generic / swallowed catches left.
- **Still `error(…)` on purpose:** `TransactionRepositoryAdapter` (must stay retryable), simulator config, Redis id
  generation, `PaymentTxEntityMapper`'s unknown tx type (technical "can't happen").
- **Open:** `PaymentValidator` → `RequestValidationException`; the callers' remaining decisions (step-4 X-ray).

## Open

- `RequestValidationException` subtypes for the account rules (phase 2).

Back-office APIs (`payment-consumers`, one `@RestControllerAdvice` per controller): a missing payment, tx or account is
the domain's own not-found (`PaymentDomainException.PaymentNotFoundException`, `LedgerDomainException.TxNotFoundException`,
`AccountDomainException.MerchantAccountNotFoundException` / `SellerAccountNotFoundException`) → 404.
