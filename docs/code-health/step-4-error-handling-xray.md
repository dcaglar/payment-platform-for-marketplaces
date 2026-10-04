# Step 4 X-ray: error handling, case by case (2026-10-05)

Every error-handling finding of detekt (71 findings at 68 places), read with its code and judged against the
standard in root `CLAUDE.md` §5. **Nothing is changed yet**: this is the diagnosis to decide on before fixing.
Modules in dependency order. Line numbers as of the step-3 code.

detekt rules: `TooGenericExceptionCaught` (catches `Exception` / `Throwable`), `SwallowedException` (the caught
error is neither rethrown nor logged with it), `UseCheckOrError` (`throw IllegalStateException(...)` where Kotlin
has `error(...)` / `check(...)`), `UseRequire` (same for `IllegalArgumentException` → `require(...)`).

**Verdicts**
- **BUG**: behaves wrong today (lost information, wrong status, wrong retry); fix.
- **CHANGE**: works, but breaks a §5 rule or is wider than needed; small fix.
- **STYLE**: `throw IllegalStateException(...)` → `error(...)`, no behaviour change.
- **KEEP**: correct on purpose; keep, with `@Suppress("Rule") // reason` so the baseline doesn't hide it.
- **DECIDE**: needs your decision (business behaviour, or code that may go).

## Summary

| Verdict | Places |
|---|---|
| BUG | 7 places (plus 4 more lines with the same `${...}` message bug, pattern 1) |
| CHANGE | 22 |
| STYLE | 17 |
| KEEP | 21 |
| DECIDE | 1 place (#3), plus the open question on domain status guards (see the end) |

### Patterns across modules

1. **Messages that print `${...}` literally** (BUG): in a normal string, `\${x}` is the text `${x}`, not the value
   of `x`. 9 log lines and exception messages show the placeholder instead of the payment id / status:
   `ProcessPspResultProcessingService` 153, 157, 169; `ProcessCaptureService` 36, 41, 55;
   `CapturePspPerformedConsumer` 37; `CaptureCommandExecutor` 50; `CaptureRetryRedisCache` 115. Plus
   `PaymentTxEntityMapper` 212: `{entity.txId}` without `$`. (The `\${...}` in `@Value(...)` annotations are
   correct: there it is Spring's placeholder.)
2. **Kafka consumers log and rethrow** (CHANGE, 7 consumers): §5 says log once, at the layer that handles the
   error. For a consumer that's the shared `DefaultErrorHandler` (retries, then the DLQ, whose record carries the
   error class, message and stack trace as headers). The `catch (e: Exception) { logger.error(...); throw e }`
   in each consumer is the catch-log-rethrow §5 forbids: one failure is logged 6 times (first try + 5 retries),
   plus Spring's own `Backoff … exhausted` line. But the consumer's log runs inside `EventLogContext` (event id,
   trace) and names the payment; Spring's line only says `topic-partition@offset`. Fix: remove the try/catch in
   the consumers **and** log once in our own recoverer (`KafkaTypedConsumerFactoryConfig`), with event id,
   aggregate id, event type, topic and offset and the exception, right before it sends the record to the DLQ.
3. **Background jobs count failures and rethrow** (KEEP, 6 places): `catch (t: Throwable) { counter.add(1); throw t }`
   only feeds a metric and rethrows. Legit; narrowing to `Exception` would stop counting `Error`s, so keep as is.
4. **`throw IllegalStateException` on "not found"** (STYLE, 17): same behaviour with `error("...")`.

---

## common, common-test, common-kafka
No findings.

## payment-domain

| # | Place | Rule | What happens | Verdict | Proposal |
|---|---|---|---|---|---|
| 1 | `Payment.kt:221` reconcileSettlement | UseCheckOrError | no UNMATCHED capture row → `IllegalStateException` | STYLE | `?: error("...")` |

## payment-application

| # | Place | Rule | What happens | Verdict | Proposal |
|---|---|---|---|---|---|
| 2 | `AuthorizePaymentIntentService.kt:91` | TooGenericExceptionCaught | any PSP-call error: decides FAILED / back to CREATED, then rethrows | CHANGE | behaviour is right (§5: the error decides the intent status), but catch exactly the `ExternalPspException` family with an exhaustive `when` (see "PSP exceptions" below) instead of `Exception`: same behaviour for every path (traced 2026-10-05), any other error simply propagates, and a new PSP exception type forces a decision at compile time |
| 3 | `AuthorizePaymentIntentService.kt:104` | TooGenericExceptionCaught | PSP answered but saving failed: logged, intent stays PENDING_AUTH, returned as 202 | DECIDE | the catch is right, but **nothing moves this PENDING_AUTH on**: a new authorize call returns it as-is (line 54), and I found no status check / job. §5: "every pending state needs an owner". Needs an owner (e.g. a status check with the PSP), or a decision to accept it for now |
| 4 | `CreatePaymentIntentService.kt:70` | SwallowedException | PSP refused to create the intent: marked FAILED, returned | CHANGE | the decision is right (§5: a refusal is an answer), but the cause is lost: nobody logs why the PSP refused. Log it once here (warn, with `e`) |
| 5 | `IdempotencyService.kt:43` | TooGenericExceptionCaught | any error in the first request: deletes the pending key so the client can retry, rethrows | KEEP | releases the lock for every error type on purpose; `@Suppress` |
| 6 | `IdempotencyService.kt:48` | TooGenericExceptionCaught | deleting the key failed: added as suppressed to the original error | KEEP | the original error stays the one reported; `@Suppress` |
| 7 | `ProcessCaptureService.kt:52` | TooGenericExceptionCaught | **every** error of a capture (also "payment not found", a PSP refusal, a bug) → logged, scheduled for retry; after 5 tries only a log line | BUG | classify by meaning (§5), with the same `ExternalPspException` `when` as authorize: retry Transient (and Unknown, same idempotency key), never Permanent. After the last retry the capture is dropped with a log line only: nothing owns that payment any more (DECIDE what should happen: DLQ / FAILED status). Also `InterruptedException` from `future.get` loses the interrupt flag |
| 8 | `ProcessCaptureService.kt:41` | UseCheckOrError | payment missing → exception, caught by #7 and retried | STYLE | `error(...)`; plus the message bug (pattern 1) |
| 9 | `RecordCaptureSubmissionService.kt:36` | UseCheckOrError | payment missing | STYLE | `error(...)` |
| 10 | `RecordCaptureSubmissionService.kt:62` | UseCheckOrError | merchant missing | STYLE | `error(...)` |
| 11 | `ProcessPspResultProcessingService.kt:110` | UseCheckOrError | merchant missing | STYLE | `error(...)` |
| 12 | `ProcessPspResultProcessingService.kt:153` | UseCheckOrError | payment missing; message prints `${event.publicPaymentIntentId}` literally | BUG | `error(...)` with a real `$` (pattern 1); also line 157 (`require` message) |
| 13 | `ProcessPspResultProcessingService.kt:169` | UseCheckOrError | pending capture tx missing; message prints `${payment.paymentId.value}` literally | BUG | same |
| 14 | `ProcessPspResultProcessingService.kt:245` | UseCheckOrError | payment missing | STYLE | `error(...)` |
| 15 | `ProcessPspResultProcessingService.kt:249` | UseCheckOrError | transfer missing | STYLE | `error(...)` |
| 16 | `ProcessPspResultProcessingService.kt:316` | UseCheckOrError | payment missing | STYLE | `error(...)` |

Also seen, not a detekt finding: several services turn the id with `toLongOrNull() ?: 0L` and then look up id 0,
so a malformed id shows up as "payment not found" instead of "invalid id". Worth fixing alongside #9 / #14.

## payment-infrastructure

| # | Place | Rule | What happens | Verdict | Proposal |
|---|---|---|---|---|---|
| 17 | `CaptureRetryRedisCache.kt:113` | SwallowedException | a retry item can't be read: dropped; the log prints `${e.message}` literally and not the exception | BUG | log at error **with** `e` and an id from the raw item: a dropped capture retry is a capture that will never happen, so it must be findable. Catch the JSON exception, not `Exception` |
| 18 | `CaptureRetryQueueAdapter.kt:45` | TooGenericExceptionCaught | catch, log, rethrow | CHANGE | catch-log-rethrow (§5 "never"): remove the try/catch, the caller handles it |
| 19 | `ResilientExecutionAdapter.kt:36` | SwallowedException | PSP call timed out: fallback returned, the call continues in background | KEEP | the timeout is an expected outcome, not an error; `@Suppress` |
| 20 | `ResilientExecutionAdapter.kt:58` | TooGenericExceptionCaught | unwraps the real cause and rethrows | CHANGE | catch only `ExecutionException` (the wrapper); `InterruptedException` must restore the interrupt flag |
| 21 | `SnowflakeIdGeneratorAdapter.kt:23` | SwallowedException + TooGeneric | pod name not ending in a number → hash fallback | CHANGE | `lastPart.toIntOrNull()`: no exception needed at all |
| 22 | `RedisIdGeneratorPortAdapter.kt:12` | UseCheckOrError | Redis returned null | STYLE | `error(...)` |

## common-db

| # | Place | Rule | What happens | Verdict | Proposal |
|---|---|---|---|---|---|
| 23 | `AbstractOutboxPartitionCreator.kt:42` | TooGenericExceptionCaught | creating the next partition failed: logged, **not** rethrown | CHANGE | the jobs around it (#38–40, #56–58) count failures in a metric, but this catch hides the failure from them, so the metric never sees it. A missing partition makes outbox inserts fail later. Rethrow (catch `DataAccessException` if anything) |
| 24 | `AbstractOutboxPartitionCreator.kt:86` | TooGenericExceptionCaught | pruning failed: logged, not rethrown | CHANGE | same as #23 |
| 25 | `AbstractOutboxPartitionCreator.kt:121` | TooGenericExceptionCaught | VACUUM of one partition failed: logged, continues with the next | KEEP | best effort per partition is the right choice; catch `DataAccessException`, `@Suppress` not needed then |
| 26 | `PaymentTxEntityMapper.kt:211` | UseCheckOrError | unknown tx type in a row; message has `{entity.txId}` without `$` | BUG | `error("... txId=${entity.txId}")` |

## payment-service

| # | Place | Rule | What happens | Verdict | Proposal |
|---|---|---|---|---|---|
| 27 | `EdgeApiGracefulShutdownHook.kt:32` | TooGenericExceptionCaught | outbox check fails during shutdown: logged, shutdown continues | KEEP | right: never hang shutdown; `Exception` instead of `Throwable`, `@Suppress` |
| 28 | `HttpPspAuthorizationGatewayAdapter.kt:204` | TooGenericExceptionCaught | PSP body unreadable → `PspUnknownException` | CHANGE | exactly the §5 adapter role; catch the JSON exception type only |
| 29 | `StripePspAuthorizationGatewayAdapter.kt:67` | TooGenericExceptionCaught | logs (garbled text "calcretepapemyentintet api failed") then translates and throws | CHANGE | see below: Stripe |
| 30 | `StripePspAuthorizationGatewayAdapter.kt:81` | SwallowedException | card declined → DECLINED | CHANGE | §5-correct (a decline is a result); see Stripe |
| 31 | `StripePspAuthorizationGatewayAdapter.kt:84` | TooGenericExceptionCaught | translates any Stripe error | CHANGE | see Stripe |
| 32 | `StripePspAuthorizationGatewayAdapter.kt:99` | TooGenericExceptionCaught | same, for retrieve | CHANGE | see Stripe |
| 33 | `WebhookController.kt:42` | TooGenericExceptionCaught | any error while processing a Stripe webhook → logged, **400** | CHANGE | see Stripe. If it stays: a failure on our side must not be a 400 (that says the caller was wrong); let it go to the controller advice (§5) |
| 34 | `UuidV7Validator.kt:12` | SwallowedException | not a UUID → invalid | KEEP | invalid input is a result; `@Suppress` |
| 35 | `SimulatedPspAuthorizationGatewayAdapter.kt:34` | UseCheckOrError | simulator scenario missing | STYLE | `error(...)` |
| 36 | `AuthorizationNetworkSimulator.kt:16` | UseCheckOrError | same | STYLE | `error(...)` |
| 37 | `PaymentApiIntegrationTest.kt:866` (test) | SwallowedException | fills a thread pool until it refuses | KEEP | the refusal is the loop's end condition; `@Suppress` |

**Stripe (#29–33): keep the adapter, fix its translation.** Checked against Stripe's own guidance
([error handling](https://docs.stripe.com/error-handling?lang=java), [errors](https://docs.stripe.com/api/errors)) and
the SDK's class hierarchy (stripe-java 31.0.0). Every Stripe exception extends `StripeException`, but grouped by
where the error comes from, not by "retry or not". Problems found:
- `ApiConnectionException` (network) lands in the `is StripeException` branch, has no status code, and is then
  classified by **message text** ("timeout", "connection refused"): otherwise **Permanent → the payment is marked
  FAILED** although Stripe may have authorized it. Stripe: "Treat the result of the API call as indeterminate".
  The branches meant for it (`is ApiConnectionException`, `is SocketTimeoutException`) can never be reached.
- `ApiException` (5xx) → Transient; Stripe: indeterminate, so **Unknown**.
- `isRetryableError` compares `e.code` with Stripe error **types** (`api_error`, `idempotency_error`, ...); codes are
  values like `card_declined`, `rate_limit`. Those branches never match.
- The `try` around `confirm` also covers our own `pspReferenceOrThrow()` / `markAuthorized(...)`: our bugs become
  `PspUnknownException` (§5: adapters translate foreign exceptions only).
- `AuthenticationException` / `PermissionException` (our API key) mark the buyer's payment FAILED; they need an alert.
- `updatePaymentIntentStatus`: an unknown Stripe status becomes DECLINED instead of "don't know".
- #29 logs a garbled line ("calcretepapemyentintet api failed") before translating: the error is logged again
  where it's handled.

Translation following Stripe's guidance (the `try` wraps only the Stripe call; `CardException` on confirm stays a
decline; the SDK already retries network errors itself, `setMaxNetworkRetries(2)`):

| Stripe | → our `ExternalPspException` |
|---|---|
| `RateLimitException` (429; check before `ApiException`, it's a subclass) | `PspTransientException` |
| `ApiConnectionException`, `ApiException` | `PspUnknownException` |
| `InvalidRequestException`, `IdempotencyException`, `AuthenticationException` (+ `PermissionException`) | `PspPermanentException` (our request / our key: alert) |
| any other `StripeException` | `PspUnknownException` |

#33 `WebhookController`: a failure on our side must not answer 400 (that says the caller was wrong); let it go to
the controller advice (§5).

## payment-edge-workers

| # | Place | Rule | What happens | Verdict | Proposal |
|---|---|---|---|---|---|
| 38 | `LocalOutboxMaintenanceJob.kt:45` | TooGenericExceptionCaught | count + rethrow | KEEP | pattern 3 |
| 39 | `LocalOutboxMaintenanceJob.kt:87` | TooGenericExceptionCaught | count + rethrow | KEEP | pattern 3 |
| 40 | `LocalOutboxMaintenanceJob.kt:105` | TooGenericExceptionCaught | count + rethrow | KEEP | pattern 3 |
| 41 | `LocalOutboxMaintenanceJob.kt:66` | SwallowedException + TooGeneric | waiting for the outbox table: every error ignored, 20 tries | CHANGE | the reason is never logged (DB down? wrong credentials?): log it (debug/warn) with the error |
| 42 | `LocalOutboxDispatchWorker.kt:68` | TooGenericExceptionCaught | forwarding a batch failed → logged, rows unclaimed for the next run | CHANGE | the right decision; `Exception` instead of `Throwable` (don't swallow `OutOfMemoryError`) |
| 43 | `LocalOutboxDispatchWorker.kt:112` | TooGenericExceptionCaught | unclaim failed → the reclaimer picks the rows up | KEEP | the pending rows have an owner (the reclaimer); `Exception`, `@Suppress` |
| 44 | `LocalOutboxDispatchWorker.kt:123` | TooGenericExceptionCaught | count + rethrow | KEEP | pattern 3 |
| 45 | `LocalOutboxStoreAndForwardJob.kt:106` | TooGenericExceptionCaught | deleting the watermark at shutdown failed: logged | KEEP | shutdown must continue; `Exception`, `@Suppress` |

## payment-consumers

| # | Place | Rule | What happens | Verdict | Proposal |
|---|---|---|---|---|---|
| 46 | `TransactionConsumer.kt:43` | TooGenericExceptionCaught | log + rethrow | CHANGE | pattern 2 |
| 47 | `PspResultConsumer.kt:86` | TooGenericExceptionCaught | log + rethrow | CHANGE | pattern 2 |
| 48 | `CaptureCommandExecutor.kt:48` | TooGenericExceptionCaught | log + rethrow; message prints `${...}` literally | BUG | pattern 2 + pattern 1 |
| 49 | `AccountCreationCommandExecutor.kt:47` | TooGenericExceptionCaught | log + rethrow | CHANGE | pattern 2 |
| 50 | `CapturePspPerformedConsumer.kt:48` | TooGenericExceptionCaught | log + rethrow (and line 37 prints `${...}` literally) | BUG | pattern 2 + pattern 1 |
| 51 | `GrossCaptureAllocationConsumer.kt:213` | TooGenericExceptionCaught | log + rethrow | CHANGE | pattern 2 |
| 52 | `GrossCaptureAllocationConsumer.kt:96` | UseCheckOrError | merchant missing | STYLE | `error(...)` |
| 53 | `GrossCaptureAllocationConsumer.kt:176` | UseRequire | unknown split account type | STYLE | `require(...)`-style or `throw IllegalArgumentException` kept; note it's **not retried** (IllegalArgumentException is in the error handler's not-retryable list) → straight to the DLQ, which is right for bad data |
| 54 | `AccountBalanceSnapshotJob.kt:55` | TooGenericExceptionCaught | snapshot merge failed: logged, next run tries again | CHANGE | right decision (scheduled, retried next tick), but it also swallows bugs silently except for one log; rethrow, or catch `DataAccessException` only |
| 55 | `AccountProfileRedisCache.kt:26` | TooGenericExceptionCaught | cached profile unreadable → treated as not cached | CHANGE | right (cache is best effort, the DB is the source); catch the JSON exception only |
| 56 | `AccountProfileRedisCache.kt:40` | TooGenericExceptionCaught | writing the cache failed → logged | CHANGE | same; catch JSON + Redis exceptions only |
| 57 | `SimulatedPspCaptureGatewayAdapter.kt:36` | UseCheckOrError | scenario missing | STYLE | `error(...)` |
| 58 | `SimulatedPspCaptureGatewayAdapter.kt:40` | UseCheckOrError | scenario missing | STYLE | `error(...)` |
| 59 | `TransactionRepositoryAdapter.kt:68` | UseCheckOrError | transaction row not saved yet → fail so the event is retried | STYLE | `check(...)` |
| 60 | `BalanceService.kt:90` | UseCheckOrError | owner has accounts in several currencies | STYLE | `check(...)` |
| 61 | `LedgerMapperIntegrationTest.kt:423` (test) | SwallowedException | timeout = "still blocked on a lock" | KEEP | the timeout is the answer; `@Suppress` |
| 62 | `PaymentTxMapperIntegrationTest.kt:382` (test) | SwallowedException | same | KEEP | same |

## payment-central-relay

| # | Place | Rule | What happens | Verdict | Proposal |
|---|---|---|---|---|---|
| 63 | `CentralOutboxKafkaHelper.kt:25` | TooGenericExceptionCaught | a synchronous publish error becomes a failed future | KEEP | turns both failure paths into one; `@Suppress` |
| 64 | `CentralOutboxDispatchWorker.kt:51` | TooGenericExceptionCaught | publish failed: logged, row unclaimed, the aggregate's chain stops (keeps order) | KEEP | deliberate: later events of the same aggregate must wait; `@Suppress` |
| 65 | `CentralOutboxDispatchWorker.kt:80` | TooGenericExceptionCaught | unclaim failed → reclaimer | KEEP | as #43 |
| 66 | `CentralOutboxMaintenanceJob.kt:47` | TooGenericExceptionCaught | count + rethrow | KEEP | pattern 3 |
| 67 | `CentralOutboxMaintenanceJob.kt:68` | TooGenericExceptionCaught | count + rethrow | KEEP | pattern 3 |
| 68 | `CentralOutboxMaintenanceJob.kt:89` | TooGenericExceptionCaught | count + rethrow | KEEP | pattern 3 |

## e2e-tests
No findings.

---

## PSP exceptions: one family, one meaning (decided 2026-10-05)

Adapters translate whatever the PSP does (Stripe exceptions, HTTP status codes, the simulator) into **our**
exceptions; everything after the adapter decides by type only. Today the meaning is not written on the types and
callers disagree: authorize retries Transient + Unknown and fails on Permanent, capture (#7) retries everything,
the Kafka error handler sees plain `RuntimeException`s and retries them all.

Plan:
1. One sealed parent **`ExternalPspException`** (replaces the misnamed `PspCreateIntentException`), with the meaning
   written on each type (§5 categories):
   - `PspTransientException`: not done, may work next time → **retry**.
   - `PspUnknownException`: may have been done → **retry only with the same idempotency key**, or ask the PSP.
   - `PspPermanentException`: refused, won't work on retry; our request or configuration is wrong → **no retry, alert**.
2. Every PSP adapter (HTTP, Stripe, simulators; authorization and capture) maps to this family only. The port
   KDoc (`PspAuthorizationGatewayPort`, `PspCaptureGatewayPort`) states it: it's the contract the services rely on.
3. Every caller decides with an exhaustive `when` over the family: authorize (#2), capture (#7).
4. The Kafka error handler gets `PspPermanentException` on its not-retryable list.
5. `PspUnavailableException` is unused: delete. `PspInvalidPaymentException` (an unusable payment method, from the
   Stripe adapter only; its parameter is misspelled `mesage`) stays a validation error.

Also found while tracing (not error handling, for later): the HTTP adapter silently sends **no** payment method when
it isn't a card token, while the Stripe adapter throws `PspInvalidPaymentException`: two adapters, two behaviours.

## Decisions needed before fixing

1. **#3 PENDING_AUTH without an owner** (PSP answered, saving failed): add an owner now (which one?), or accept
   and note it as known?
2. **#7 capture retries**: after 5 failed tries, what should happen to the payment: DLQ, a FAILED-like status,
   an alert? And should a permanent PSP refusal skip the retries?
3. ~~#29–33 Stripe~~: decided, keep the adapter and fix its translation (see "PSP exceptions" above).
4. **Domain status guards** (e.g. `Payment.kt:215`, "can't reconcile unless CAPTURED"): `require` throws
   `IllegalArgumentException` (not retried by the Kafka error handler → DLQ), `check` throws `IllegalStateException`
   (retried). Semantically these are state rules (`check`); today event order is guaranteed (same outbox write,
   same partition), so nothing is broken. With a real acquirer, webhook and settlement file can arrive in either
   order and retrying would heal it. Changing it changes the convention in `payment-domain/CLAUDE.md`
   ("a transition `require(...)`s the legal source status"): switch all guards, or keep?
5. Everything else (BUG / CHANGE / STYLE / KEEP) can go ahead as proposed.
