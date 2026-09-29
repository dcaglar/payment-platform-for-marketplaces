# Plans & summaries

Running log of plans and summaries. Newest first.

---

## Plan: multiple captures per payment, no Capture entity (2026-09-28) — DEFERRED

Status: not implemented. For now only auto capture of the full amount (from `processAuthorized`); `markSentForSettle`
stays AUTHORIZED-only. Keep this plan for when manual/partial capture is picked up.

Decisions: no Capture entity/table. A `captureId` (Snowflake) is born where `CaptureRequested` is built and travels on
every capture event. It is stored as a `capture_id` column on the CAPTURE `payment_tx` row. "Sum of captures ≤ total"
is checked at submission record.

Verified blockers today (code):
- `PspCaptureGatewayPort.capture(payment)` gets no amount, so a partial capture can't be sent to the PSP.
- `CaptureSubmitted` / `CaptureConfirmed` / `SettlementReceived` dedup id = `pi:eventType`, so a 2nd capture's events are skipped.
- `markSentForSettle` needs `AUTHORIZED`; `processCaptureConfirmed` needs `SENT_FOR_SETTLE`, so only one capture fits.
- `processCaptureConfirmed` takes the first PENDING CAPTURE tx; `reconcileCaptureSettlement` takes the first UNMATCHED one.
- `recordSubmission` makes a new `txId` on every replay, so `ON CONFLICT (tx_id)` never dedups.

Changes:
1. **Events**: add `captureId` to `CaptureRequested`, `CaptureSubmitted`, `CaptureConfirmed`, `SettlementReceived`.
   Dedup ids become `pi:captureId:eventType` (`CaptureRequested` keeps `:attempt`).
2. **Where the id is born**: `CapturePaymentService` (edge, manual) and `processAuthorized` (central, auto) generate it
   via `IdGeneratorPort`.
3. **PSP call**: `PspCaptureGatewayPort.capture(payment, amount)`; `ProcessCaptureService` passes the requested amount.
4. **DB**: Liquibase changeset adds `payment_tx.capture_id BIGINT NULL` + unique index on `capture_id`.
   `CaptureTx.captureId`, entity, `PaymentTxEntityMapper`, `PaymentTxMapper.xml` updated.
5. **Domain (`Payment`)**:
   - `markSentForSettle` allowed from `AUTHORIZED` (→ `SENT_FOR_SETTLE`) and `PARTIALLY_CAPTURED` (stays `PARTIALLY_CAPTURED`;
     the PENDING CAPTURE tx shows the capture in flight).
   - New check `captured + pending captures + new ≤ total`, used at submission.
6. **`RecordCaptureSubmissionService`**: if a CAPTURE tx with this `captureId` already exists → replay, skip. Otherwise
   run the sum check and create the PENDING CAPTURE tx with `captureId`.
7. **`processCaptureConfirmed`**: accept `SENT_FOR_SETTLE` or `PARTIALLY_CAPTURED` (as `applyCapture` already does);
   find the CAPTURE tx by `captureId`. Journal id keyed by `captureId`.
8. **Settlement**: `reconcileCaptureSettlement` matches the CAPTURE tx by `captureId` instead of the first UNMATCHED.
9. **Tests**: unit tests in `ProcessPspResultProcessingServiceTest` + a `Payment` domain test for the sum check and
   the second-capture transitions.

Out of scope: the `processAuthorized` replay creating a 2nd Payment (no unique on `payments.payment_intent_id`);
refunds; the edge having no check against the central Payment.

---

## The MoR flow: what triggers what (2026-09-28)

Grounding: `e2e-tests/.../PaymentFlowE2EIntegrationTest.kt` (M0–M13) + targeted code reads. Example: MARKETPLACE-5,
3,000 EUR, splits SELLER-5-1 1,400 · Commission 100 · SELLER-5-2 1,400 · Commission 100.

Every hop between modules is: **write state + outbox row in one DB transaction → a poller or consumer picks it up.**
Kafka topic per event type comes from `PaymentEventMetadataCatalog`.

| # | Trigger | Component (module) | Writes, in ONE transaction | Emits | e2e |
|---|---|---|---|---|---|
| 1 | `POST /api/v1/payments` | `PaymentController` → `CreatePaymentIntentService` (payment-service) | intent `CREATED_PENDING` → PSP create (≤3 s) → `CREATED`; idempotency key | nothing (no outbox) | M0 |
| 2 | `POST /payments/{pi}/authorize` | `AuthorizePaymentIntentService` (payment-service) | PSP authorize (sync); intent `AUTHORIZED` + **local** outbox row | `payment_authorized` (local outbox) | M1–M2 |
| 3 | scheduler, every 500 ms (30 s initial delay) | `LocalOutboxStoreAndForwardJob` (payment-edge-workers) | copy row into **central** outbox; local row → `SENT` | row in central outbox | M3–M4 |
| 4 | scheduler, every 5 s (15 s initial delay), rows ≤ T_safe | `OutboxRelayJob` → `CentralOutboxDispatchWorker` (payment-central-relay) | publish raw bytes; central row → `SENT` | Kafka `payment.psp.results` | M5 |
| 5 | `payment_authorized` | `PspResultConsumer` → `processAuthorized` (payment-consumers) | `Payment` `AUTHORIZED` + `AuthorizationTx` SUCCESS + **AUTHORIZATION journal** | outbox: `capture_requested`, `journal_entries_recorded` | M6–M7 |
| 6 | `capture_requested` (`gateway.capture.requested`) | `CaptureCommandExecutor` → `ProcessCaptureService` | PSP capture call (2 s timeout); **no state change** (network worker); failure → retry queue with backoff, max 5 | outbox: `capture_submitted` | M8 |
| 7 | `capture_submitted` (`gateway.capture.submitted`) | `CapturePspPerformedConsumer` → `RecordCaptureSubmissionService` | `Payment` `SENT_FOR_SETTLE` + `CaptureTx` PENDING; **simulation target only (MARKETPLACE-5):** also emits the provider's replies | outbox: `capture_confirmed`, `settlement_received` (fee 1.5% → 45) | M8 |
| 8 | `capture_confirmed` (`payment.psp.results`) | `PspResultConsumer` → `processCaptureConfirmed` | `Payment` `CAPTURED` + `CaptureTx` SUCCESS + **CAPTURE journal** | outbox: `journal_entries_recorded` | M9 |
| 9 | `journal_entries_recorded` containing a CAPTURE (`journal.entries.recorded`) | `GrossCaptureAllocationConsumer` → `RecordInternalTransferSubmissionService` (once per transfer) | per split: `InternalTransfer` `SENT_FOR_TRANSFER` (suspense → `split.account`); plus one COMMISSION_FEE transfer of a fixed 50 | outbox: `internal_transfer_command` × (4 splits + 1 fee) | M10 |
| 10 | `internal_transfer_command` (`payment.psp.results`) | `PspResultConsumer` → `processInternalTransferCommand` | `InternalTransfer` `TRANSFERRED` + **INTERNAL_TRANSFER / COMMISSION_FEE journal** | outbox: `journal_entries_recorded` | M10 |
| 11 | `settlement_received` (`payment.psp.results`) | `PspResultConsumer` → `processSettlementLineReconciled` | reconcile: `CaptureTx` `MATCHED`, `Payment` `SETTLED`, `SettleTx` + **SETTLEMENT journal** (cash 2,955 · fee 45 · receivable 3,000) | outbox: `journal_entries_recorded` | M11–M12 |
| – | every `journal_entries_recorded` | `AccountBalanceConsumer` | Redis balance deltas (projection; snapshot job every 1 min) | – | – |

Not triggered by this flow (e2e asserts none exist): `REFUND`, `PAYOUT`, `REVENUE_RECOGNITION`.

---

## ON HOLD — Proposal: clear names for the MoR ledger (2026-09-28)
_Paused until the flow above is fully agreed._

### Context
All escrow experiments were reverted; the code is the MoR platform only (we hold the money, pay sub-sellers and
merchants). Reviewing `JournalEntryTest` and every recipe's call site showed the account names are hard to read:
mixed perspectives, one concept with several names, and "escrow" used for something that is not escrow.
Goal: names that describe the domain on their own, from **our** perspective as the Merchant of Record.

### Naming rules
1. **One perspective: ours.** Every account is named as it sits in *our* books.
2. **Grammar: `<PARTY>_<PURPOSE>_<NATURE>`.**
   - Parties: `PLATFORM` (us), `PSP`, `AUTH` (card network hold), `MERCHANT` (the marketplace operator, matches the API field `merchantAccount`), `SELLER` (sub-seller).
   - Nature suffix, fixed set: `_CASH` (real money in a bank account), `_RECEIVABLE` (promise to get), `_PAYABLE` (promise to pay), `_REVENUE`, `_EXPENSE`.
3. **One word per concept:** *suspense* (never "pool"), *seller balance* ("wallet" only in prose), *reserve* (never "escrow").
4. **"Escrow" only for money a neutral third party holds for others.** We don't have that in the MoR flow.

### Accounts: current → proposed
| Current | What `JournalEntryTest` says it is | Proposed |
|---|---|---|
| `PLATFORM_CASH` | "Our physical bank balance" | keep |
| `PSP_RECEIVABLES` | "Adyen now legally owes us real cash" | `PSP_RECEIVABLE` (singular, like the others) |
| `AUTH_RECEIVABLE` | "We expect €100 from the card network… no physical money has moved" | `AUTH_HOLD_RECEIVABLE` (says it's a hold / memo) |
| `AUTH_LIABILITY` | "We owe €100 to the merchant once it clears" (mirror of the hold) | `AUTH_HOLD_PAYABLE` |
| `MERCHANT_GROSS_CAPTURE_SUSPENSE` | "We owe this gross money to the merchant ecosystem"; must drain to 0 (test: "Suspense Pool", recipe param: `merchantGrossPool`) | `MERCHANT_SUSPENSE_PAYABLE` |
| `MARKETPLACE_SELLER_BALANCE_ACCOUNT` | "a specific sub-seller's wallet… we now owe this sub-seller" | `SELLER_BALANCE_PAYABLE` |
| `MARKETPLACE_COMMISSION_REVENUE_BALANCE_ACCOUNT` | "Operator Commission Account… the operator's payable earnings" (the *merchant's* revenue, our liability) | `MERCHANT_COMMISSION_PAYABLE` |
| `MARKETPLACE_DIRECT_REVENUE_BALANCE_ACCOUNT` | (no test) the merchant's own direct-sales proceeds, our liability | `MERCHANT_SALES_PAYABLE` |
| `PLATFORM_COMMISSION_ESCROW` | "safety cage… could force us to return it… Deferred Revenue" | `PLATFORM_COMMISSION_RESERVE_PAYABLE` |
| `PLATFORM_OPERATIONAL_REVENUE` | "Officially record the €2 as company profit" | `PLATFORM_COMMISSION_REVENUE` |
| `PSP_FEE_EXPENSE` | "a €5 cost of doing business" | keep |
| `MARKETPLACE_MASTER_ACCOUNT` | (no test) "represents the merchant entity"; commission splits land here by accident | **decide:** remove, and point commission splits at `MERCHANT_COMMISSION_PAYABLE` |

### Journal types and recipes
| Journal type | Recipe | Proposal |
|---|---|---|
| `AUTHORIZATION` | `authHold` | keep |
| `CAPTURE` | `captureGrossAsset` | keep type; recipe `capture` |
| `INTERNAL_TRANSFER` | `internalTransfer` | keep; **add account-type checks** (today it accepts any two accounts) |
| `COMMISSION_FEE` | `commissionFeeRegistered` | type `COMMISSION_RESERVED`; recipe `reserveCommission` |
| `REVENUE_RECOGNITION` | `recognizePlatformRevenue` | keep type (standard term); recipe `recognizeCommissionRevenue` |
| `SETTLEMENT` | `settlementLineItem` | keep |
| `PAYOUT` | `payout` | keep (no caller yet: no payout job) |
| `REFUND` | `refund` | keep type; **fix recipe** (auth legs go the wrong way) |
| `PSP_FEE`, `ADJUSTMENT` | none | keep as reserved, or remove until used |

### What a rename costs
- **Kotlin only (cheap):** recipe names, parameter names (`merchantGrossPool` → `merchantSuspense`), comments, test prose.
- **Enum rename (needs a migration):** account type names are stored in `account_directory.csv` (`account_type`), and are part of **account codes** (e.g. `MERCHANT_GROSS_CAPTURE_SUSPENSE.MARKETPLACE-5.EUR`) that postings reference. Also used as strings in `PaymentSplitDto` and in e2e asserts (`account_code LIKE 'PSP_RECEIVABLES%'`).
- Suggested order: (1) Kotlin-only renames + a glossary in `payment-domain/CLAUDE.md`; (2) a convention test (suffix must match category + backing) with the legacy names on an explicit exception list; (3) enum rename + Liquibase migration later, in one go.

### Other findings from the review
- **Flow stops halfway:** nothing pays out (no payout job); nothing sends `REVENUE_RECOGNITION`, so the commission reserve never drains; refunds aren't wired.
- **Simulated provider events:** `CapturePspPerformedConsumer` (listens on `gateway.capture.submitted`) calls the capture-submission use case, whose implementation `RecordCaptureSubmissionService` moves the payment to `SENT_FOR_SETTLE` and, marked `TODO simulation`, immediately appends `capture_confirmed` and `settlement_received` to the outbox. So the consumer is the actor; there is no real PSP webhook or settlement-file path yet.- **Refund recipe/test:** DR `AUTH_LIABILITY` / CR `AUTH_RECEIVABLE` decreases both; after capture they're 0, so a refund drives them negative. The test comment ("+Liability, +Asset") contradicts the postings.
- **Commission splits** target `"<merchantAccount>.EUR"` = `MARKETPLACE_MASTER_ACCOUNT`, while the fixed 50 fee is taken from `MARKETPLACE_COMMISSION_REVENUE_BALANCE_ACCOUNT`; the e2e test only counts that a `COMMISSION_FEE` journal exists.
