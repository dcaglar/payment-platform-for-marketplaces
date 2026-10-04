# Back office — requirements

Agreed 2026-10-02. The original notes are kept at the end; where they differ, this spec wins.

## Principles
- The platform is the **Merchant of Record**: the buyer pays the platform; sellers are its suppliers.
- **The merchant has the power**: it sees its own payments and sellers and takes the actions. A seller only views its balance.
- Never stored or shown: a full card number, CVC, or bank details. A card is kept and shown only as its **brand and last 4 digits** (`CardSummary`), e.g. VISA ···· 4242 (allowed under PCI: a truncated number). A merchant never sees another merchant's data; a seller never sees another seller's.
- Ledger internals (transactions, journal entries, postings) are **finance only**.
- Every action is recorded: who, what, when.

## Users

| User | Logs in | Sees | Can do |
|---|---|---|---|
| Seller | yes | own balance only | nothing |
| Merchant (marketplace ops) | yes | own payments, own sellers | Capture, Cancel (Release 2) |
| Support (platform staff) | yes | all merchants' payments and sellers | view only |
| Finance (platform staff) | yes | everything above + ledger drill-down and postings | view only |
| Ops engineer (platform staff) | yes | flow health (Release 3) | view only |
| Admin (platform staff) | yes | — | create merchants (`POST /api/v1/accounts`, exists) |

## Balances
Unchanged for now: the existing balance API. A seller's balance is its `SELLER_PAYABLE` (what we owe it); a merchant's is `MERCHANT_DIRECT_PAYABLE` + `MERCHANT_COMMISSION_PAYABLE` with a total. No incoming / available split in Release 1.

## Which payments appear
Only payments the PSP **authorized** (a central Payment exists only then). Declined, failed and never-authorized intents are not shown, to anyone.

## Release 1 — viewing

| # | Screen | Seller | Merchant | Support | Finance |
|---|---|---|---|---|---|
| 1 | **Balance page**: the seller's balance (`GET /balances/sellers/me`) | own | — | — | — |
Layouts: [`mor-backoffice/wireframes/backoffice-pages.excalidraw`](mor-backoffice/wireframes/backoffice-pages.excalidraw) (open in excalidraw.com). Left menu: **Transactions**, **Balances**; staff first choose a merchant.

| # | Screen | Seller | Merchant | Support | Finance |
|---|---|---|---|---|---|
| 1 | **Balance page**: the seller's balance (`GET /balances/sellers/me`) | own | — | — | — |
| 2 | **Payment list** (menu Transactions): date, order no., payment id, **type** (Direct sale / Marketplace), **payment method** (card brand logo + last 4), amount, status; filters order no., type, status, date range; newest first, paginated. No seller column or filter (a payment can have many sellers) | — | own | all | all |
| 3 | **Payment detail**: buyer, order no., merchant, PSP reference, payment method (logo + last 4), amount, status timeline (authorized → captured → settled); a marketplace payment shows **all its splits** (each seller's part, the commission), a direct sale none | — | own | all | all |
| 4 | **Balances** (menu Balances): the merchant's balance on top (direct sales, marketplace commission, total), then its sellers with their balances, paginated, natural order (SELLER-5-2 before SELLER-5-10); click → that seller's balance | — | own | all | all |
| 5 | On the payment detail: **all its journal entries' postings as rows** in recorded order, one row per posting: journal entry, account, debit, credit (a settled marketplace payment: AUTHORIZATION 2 rows, CAPTURE 4, one INTERNAL_TRANSFER per split line 2 each, COMMISSION_FEE 2, SETTLEMENT 3) | — | ✗ | ✗ | ✓ |

No postings list (dropped 2026-10-02).

## Release 2 — merchant actions
1. **Capture** (full amount): only for manual-capture merchants (`isAutoCaptured = false`), only while the payment is `AUTHORIZED`; confirmation dialog; asynchronous ("Capture requested", then the result).
2. **Cancel**: releases the whole authorization while the payment is `AUTHORIZED`; confirmation dialog.
3. **Expiry warning** on authorizations getting old (e.g. 5 days).

## Release 3 — finance and ops
1. **Finance month view** per account: opening balance, in, out, closing; click through to the movements in plain words ("€14.00 credited to SELLER-5-1 for ORDER-1450").
2. **Ops flow health**: payments stuck per stage beyond a threshold, dead-letter counts, link to the payment.

## Later
- **Payouts**: today the system only allocates on paper (`SELLER_PAYABLE`); settlement lands in the platform's bank and nothing pays the sellers. Later: pay available money on a schedule (e.g. daily) through a bank/payout API; as MoR these are supplier payments (no PCI); store only the provider's reference, never the IBAN; book `PAYOUT` when confirmed.
- Partial capture, refunds, month locking, alerts (Slack/email).

## Not doing
- Export (CSV/Excel) of any screen.
- Sellers cancelling payments.
- Sellers seeing anything except their own balance.
- A postings list screen (finance sees postings per tx on the tx detail).
- Merchants seeing transactions, journal entries, postings or T-account views.
- Showing declined, failed or never-authorized payments.

## Release 1 — design

Decided 2026-10-02.

### 1. Events
| Event | Topic | Change |
|---|---|---|
| `journal_entries_recorded` | `journal.entries.recorded` (exists) | The AUTHORIZATION booking's event carries, at payment level (not in the postings): `splits`, `buyerId`, `orderId`, `pspReference`, and the card summary (`cardBrand`, `cardLast4`). Other bookings unchanged. |

**Card summary (`CardSummary`: brand + last 4).** The PSP's authorize answer gives the card's brand and last 4 digits; the
payment-service PSP adapters (HTTP/WireMock, simulator, Stripe) read them into a `CardSummary` on the payment intent
(stored in edge-db `payment_intents`). `payment_authorized` carries it to central, the AUTHORIZATION
`journal_entries_recorded` to the back office. Brands: VISA, MASTERCARD, AMEX, OTHER. Nothing else of the card is kept.

`orderId` is not on the payment today; it comes from the intent (`payment_intents.order_id`) through the events above.

### 2. Transactions: `TransactionConsumer` (in `payment-consumers`, consumer group `transaction.consumer`)
A **Transaction** is one authorized payment, denormalized for display (not to be confused with **Tx**, one PSP interaction of a payment). `TransactionConsumer` (thin, like the others) → `TransactionUseCase.updateTransactions(event)` → `TransactionService` → `TransactionRepository`. Listens to `journal.entries.recorded` only.

| Booking | `TransactionRepository` |
|---|---|
| AUTHORIZATION | `save(transaction)`: payment ids, merchant, buyer, order no., PSP reference, processing model, card summary, total, splits (domain `PaymentSplit`), authorized time |
| CAPTURE | `markCaptured(paymentId, capturedAt)` |
| SETTLEMENT | `markSettled(paymentId, settledAt)` |
| anything else | nothing |

- Idempotent: `save` inserts unless already saved; `markCaptured` / `markSettled` set the time only if still empty.
- The authorization always comes first (its event is written before a capture is even requested; one partition per merchant). Marking a transaction that is not saved fails, so the event is retried, then goes to the DLQ: nothing is lost silently.
- Settlement before capture/allocation is fine: they are separate columns.
- The status is not stored: settled → SETTLED, captured → CAPTURED, else AUTHORIZED.

### 3. Tables (central-db, Liquibase `central-transactions-tables.xml`)
| Table | Key | Columns |
|---|---|---|
| `transactions` | `payment_id` | payment intent id, public intent id, merchant, buyer, order no., PSP reference, processing model, card brand, card last 4, total amount, currency, `authorized_at`, `captured_at`, `settled_at` |
| `transaction_splits` | (`payment_id`, `line_no`) | account type (`SELLER_PAYABLE` / `MERCHANT_COMMISSION_PAYABLE`), account, amount, currency |

### 4. Read paths (where the screens read from)
| Datasource | Points at | Used by |
|---|---|---|
| `central-db-replica` | central-db **read replica** (Bitnami `architecture: replication`) | merchant, support and seller screens (projection tables) |
| `central-db` (the normal one) | the main central-db that all consumers write to; finance gets its **own small connection pool** on it, read-only, `statement_timeout` 5s | finance drill-down (`payment_tx`, `journal_entries`, `postings`): few users, always latest |

Projection **writes** go to the normal `central-db` like all writes; the replica copies them a moment later. Replica lag (~1s) is acceptable: actions are asynchronous anyway.

### 5. REST (in `payment-consumers`, read-only; the web app is "Back office", the API carries no back-office name)
`/api/v1/payments` is routed to the edge (`payment-service`) by the ingress, so the read APIs have their own prefixes
(`/api/v1/balances`, `/api/v1/transactions`, `/api/v1/txs`), each one ingress route and one URL rule.

**URL convention** (every API): `/<api>/merchants/me…` = the token's merchant (claim `merchant_id`), `/<api>/merchants/{merchantAccount}…` =
staff (`merchant:all`) naming the merchant. One kind of caller per endpoint, checked by its `@PreAuthorize`; an id is
looked up together with its merchant, so another merchant's record is a `404`, the same as an unknown id. The full list
is in "Security" below (#5–#11).

### 6. Front end: `mor-backoffice`
- **Stack**: React + Vite + TypeScript, with its own small Node server ("backend for frontend"). **Runs locally** for now (`npm run dev`); image + Helm chart later.
- **Login**: Keycloak's own login page, client `backoffice-ui` (public, authorization code + PKCE), done by the server (`openid-client`). The server keeps the tokens in the session and calls the API with the user's token; **the browser never holds a token**, only a session cookie.
- **The API's rules protect the data**; the page only shows what a role may use.
- **Screens**: as in "Release 1 — viewing" and the wireframes.

### 7. Build order
| Step | What |
|---|---|
| 1a | Edge: `payment_authorized` + `orderId`, `pspReference` (done) |
| 1b | Central: AUTHORIZATION `journal_entries_recorded` carries splits, buyer, order no., PSP reference |
| 2 | `transactions` tables + `TransactionConsumer` |
| 3 | API: transactions, sellers; `SUPPORT` role, merchant login users |
| 3-UI | `mor-backoffice`: login, seller, merchant and support screens (done) |
| 3b | Card summary (brand + last 4) from the PSP to the back office; list, detail and balances pages as in the wireframes |
| 4 | Finance API: a payment's txs and its journal entries without a tx; a tx with its journal entries (domain `Tx`, `JournalEntry`, `Posting` read back as they are). `Tx.SettleTx` no longer copies the PSP fee and net cash: they are booked only in the SETTLEMENT journal entry |
| 4-UI | Finance screen: the payment's postings as rows (journal entry, account, debit, credit) on the payment detail |
| 4b | central-db read replica + replica datasource (separate step, infrastructure) |
| 5 | e2e: an authorized payment visible through the API (done) |

## Security: endpoints, permissions, Keycloak (agreed 2026-10-02; #1–#10 implemented, Keycloak from keycloak/realm/)

Two layers, as Adyen does it: **permissions say what a caller may do** (endpoints check only these); **claims say whose data** (`merchant_id`, `seller_id`). Ids in a URL are looked up **together with** the caller's merchant, so another merchant's record is a `404`. Staff see every merchant only through the explicit permission `merchant:all`. Sellers have no API access: a seller asks its marketplace, which calls us with its merchant credential.

### Endpoints
| # | Endpoint | Callers | Permission | Lookup / rule | Refused |
|---|---|---|---|---|---|
| 1 | `POST /api/v1/payments` | merchant backend | `payment:write` | body `merchantAccount` must equal JWT `merchant_id` | `403` |
| 2 | `GET /api/v1/payments/{paymentIntentId}` | merchant backend | `payment:read` | intent by `(id, merchant_id)` | `404` |
| 3 | `POST /api/v1/payments/{paymentIntentId}/authorize` | merchant backend | `payment:write` | intent by `(id, merchant_id)` | `404` |
| 4 | `POST /api/v1/payments/{paymentIntentId}/captures` (Release 2) | merchant backend, merchant user | `payment:write` | intent by `(id, merchant_id)` | `404` |
| 5 | `GET /api/v1/balances/merchants/me` | merchant backend, merchant user | `balance:read` | JWT `merchant_id` | `403` (no claim) |
| 5a | `GET /api/v1/balances/merchants/{merchantAccount}` | staff | `balance:read` + `merchant:all` | the merchant in the path | `404` |
| 5b | `GET /api/v1/balances/sellers/me` | seller user (back office) | `balance:read` | JWT `seller_id` | `403` (no claim) |
| 6 | `GET /api/v1/balances/merchants/me/sellers?page=&size=` (merchant) · `GET /api/v1/balances/merchants/{merchantAccount}/sellers` (staff) | merchant backend, merchant user · staff | `balance:read` | merchant: its own sellers, paged, items with `detailUrl`; staff: the merchant in the path | — |
| 7 | `GET /api/v1/balances/merchants/me/sellers/{sellerId}` (merchant) · `GET /api/v1/balances/sellers/{sellerId}` (staff) | merchant backend, merchant user · staff | `balance:read` | merchant: `(sellerId, merchant_id)`; staff: `sellerId` | `404` |
| 8 | `GET /api/v1/transactions/merchants/me?…` (merchant) · `GET /api/v1/transactions/merchants/{merchantAccount}?…` (staff) | merchant backend, merchant user · staff | `transaction:read` | merchant: its `merchant_id`; staff: the merchant in the path | — |
| 9 | `GET /api/v1/transactions/merchants/me/{paymentId}` (merchant) · `GET /api/v1/transactions/merchants/{merchantAccount}/{paymentId}` (staff) | merchant backend, merchant user · staff | `transaction:read` | merchant: `(paymentId, merchant_id)`; staff: `(paymentId, merchantAccount)` | `404` |
| 10 | `POST /api/v1/accounts` | admin | `account:write` | — | `403` |
| 11a | `GET /api/v1/txs/merchants/{merchantAccount}/payments/{paymentId}` (step 4) | finance, admin | `ledger:read` + `merchant:all` | the payment's txs and all its journal entries, by `(paymentId, merchantAccount)` | `404` |
| 11 | `GET /api/v1/txs/merchants/{merchantAccount}/{txId}` (step 4) | finance, admin | `ledger:read` + `merchant:all` | tx + journal entries + postings, by `(txId, merchantAccount)` | `404` |

Webhooks stay closed. `GET /api/v1/sellers` (built in step 3) was replaced by #6 (done).

### Keycloak (realm `ecommerce-platform`)
**Permissions (realm roles):** `payment:read`, `payment:write`, `balance:read`, `transaction:read`, `ledger:read`, `account:write`, `merchant:all`

**Bundles (composite realm roles, never checked by endpoints):**
| Bundle | Contains | Claim |
|---|---|---|
| `MERCHANT` | `payment:read`, `payment:write`, `balance:read`, `transaction:read` | `merchant_id` |
| `SELLER` | `balance:read` | `seller_id` |
| `SUPPORT` | `balance:read`, `transaction:read`, `merchant:all` | — |
| `FINANCE` | `SUPPORT` + `ledger:read` | — |
| `ADMIN` | `FINANCE` + `account:write` | — |

**Clients:**
- `merchant-api-MARKETPLACE-N` (one per merchant): confidential, client credentials; service account gets `MERCHANT`; hardcoded claim `merchant_id`.
- `backoffice-ui`: public, browser login (authorization code + PKCE); password grant only locally for test scripts; mappers put the user attributes `merchant_id` / `seller_id` into the token.

**Users (log in through `backoffice-ui`):** `merchant-N` (one per merchant, `MERCHANT`, attribute `merchant_id`), `seller-x-y` (`SELLER`, attribute `seller_id`), `support-ops` (`SUPPORT`), `finance-ops` (`FINANCE`), `backoffice-admin` (`ADMIN`).

**Removed:** client `payment-service` (payments without a merchant), `seller-api-*` clients and role `SELLER_API`, clients `customer-area-frontend` and `seller-client` (sellers use `backoffice-ui`), client `finance-service` (finance uses the back office).

**Separate fix, next:** idempotency keys scoped per merchant account (today global: one merchant reusing another's key would get the other's stored answer).

---

## Original notes (kept as written)

1-backoffice application is a web-app, merchantaccount or PspPlatformAccount login should be possible.
-if merchant account is logged in, by using jwt it should retrieve paginated paymentlist order by created-at descending, merchantacccount should not be allowed any other paymentsf rom other merchants,
in list columnsare paymentintentid paymentid, amount,currenct,merchantaccount,paymentstatus(only payment at least declined or authorized should be here, those not prmoted to payment yet,should not behere)
when user click on a element on payment list, it should be showing details of payment aggregate, in detail page, we should be able to see the tx's list of that payment, and also
when we click on the element in txlist, then it should go to tx detail page, an in tx detail page, you do  have link to payment aggregate, and also journalentries and postings displayed  as a kind of T table i mean 200 cr 200dr on that acount.

> Superseded: merchants no longer see the tx list, tx detail or T-account view (finance only).

2=when you logged osn as psplatformadmin account, then same payment list format, but this time platform level all merchants payments,
when user click on a element on payment list, it should be showing details of payment aggregate, in detail page, we should be able to see the tx's list of that payment, and also
when we click on the element in txlist, then it should go to tx detail page, an in tx detail page, you do  have link to payment aggregate, and also journalentries and postings displayed  as a kind of T table i mean 200 cr 200dr on that acount.

so i believe in order to being all this data we need to have denormalized view where on backoffice 
/backoffice/payments -> should return paginated payment list,
/**backoffice**/payments/{paymentid} should simply return a PaymentDetails respinse inclduing paymentid intentid, txlist(txid only,lazy loaded)(each elemeent is a tx belong to tpayment, and that txid also should be a link
like backoffice/txs/{txId}) -> TxDetail Page again amount, currenct txtype, and if exist ant tx metadatada, and each tx  also should point to list<JoirnalEntry> breakdown into eachjounralentry,but  
when we want to see paymentlist, it will simply call a rest endpoint

> Superseded: endpoint names are now `/api/v1/transactions`, `/api/v1/txs/{txId}`, … (see "Release 1 — design", section 5).
