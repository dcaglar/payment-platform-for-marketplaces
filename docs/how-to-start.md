# 🚀 How to Start

Follow these steps to provision auth, get a token, test the API, and run load tests locally.

This is a short, practical “start order” for spinning up the stack on OrbStack native Kubernetes, with one‑line notes on what each script does.

Prereqs (once):
- OrbStack native Kubernetes, kubectl, helm installed
- Troubleshooting connectivity? See docs/troubleshooting/connectivity.md

---s
 LOCAL ORB CLUSTER
## 0️⃣ Start the infrastructure and services

Pre-step: switch to the project root directory and make scripts executable
```bash
cd /path/to/payment-platform-for-marketplaces
chmod +x infra/scripts/*.sh
```


1) Build and push the docker images of all payment platform services to the remote image registry
- What: Builds the latest source code of payment platform servcices into remote Docker images
- Run:
```bash
infra/scripts/build-all-payment-platform-images-and-push.sh
```

2) Deploy all external infra (keycloak, redis, kafka) to the local cluster
```bash
infra/scripts/deploy-all-external-infra-local.sh
```  

3) Deploy all payment platform services to the local cluster (payment-edge-cell, payment-edge-workers, payment-central-relay, payment-consumers)
```bash
infra/scripts/deploy-payment-platform-services-local.sh
```
- Payments go through **Stripe test mode**: `STRIPE_API_KEY` must be in `edge-cell-sops-secrets.yaml` (`sops -i edge-cell-sops-secrets.yaml`); the script warns if it is missing.


4) Monitoring stack (optional)(Prometheus + Grafana) (Optional)
- What: Installs kube-prometheus-stack into monitoring.
- Run:
```bash
infra/scripts/deploy-monitoring-stack-local.sh
```

5) Exporters (Kafka & Postgresql) (Only if monitoring installed)
- What: Exposes Kafka consumer lag, offsets, etc. and central-db stats for Prometheus.
- Run (from the repo root; settings come from `infra/helm-values/<name>-values-local.yaml`):
```bash
infra/scripts/deploy-external-infra-local.sh prometheus-kafka-exporter
infra/scripts/deploy-external-infra-local.sh prometheus-postgres-exporter
```
- Check: both pods `Running`:
```bash
kubectl get pods -n payment | grep exporter
```







## 1️⃣ Set up Keycloak (realm, roles, clients, users)

Run every command from the repo root.

1) Load Keycloak
- What: loads two files into Keycloak (the e2e tests load the same two):
  - `keycloak/realm/ecommerce-platform.json` — the platform: the permissions, the roles that bundle them, the back-office login client `backoffice-ui`, staff users.
  - `keycloak/realm/merchants-seed.json` — the seed merchants and sellers, **generated** from `charts/central-db/seed/merchants.json` (the same file that seeds central-db).
- Safe to run again: it brings Keycloak to what the files say. It waits for Keycloak if it is still starting.
- Run (after section 0):
```bash
keycloak/setup-keycloak.sh
```

**Roles and what they may do** (endpoints check the permissions; the claim says whose data):

| Role | Permissions | Claim | Who |
|---|---|---|---|
| `MERCHANT` | `payment:read`, `payment:write`, `balance:read`, `transaction:read` | `merchant_id` | a marketplace's backend and its back-office users |
| `SELLER` | `balance:read` | `seller_id` | a seller, in the back office only (no API access) |
| `SUPPORT` | `balance:read`, `transaction:read`, `merchant:all` | — | platform support |
| `FINANCE` | `SUPPORT` + `ledger:read` | — | platform finance |
| `ADMIN` | `FINANCE` + `account:write` | — | platform admin |

**Who can log in (local seed data only):**

| Caller | How | Credentials |
|---|---|---|
| a merchant's backend | client credentials | client `merchant-api-MARKETPLACE-N`, secret `merchant-api-MARKETPLACE-N-secret` |
| a merchant's person | back office (`backoffice-ui`) | `marketplace-N` / `merchant123` |
| a seller | back office | `seller-N-M` / `seller123` (e.g. `seller-5-1`) |
| staff | back office | `support-ops` / `support123`, `finance-ops` / `finance123`, `backoffice-admin` / `admin123` |

2) Get tokens
- One script for every caller. It saves the token to `keycloak/output/jwt/<name>.token` and prints who it is for (claims, roles, expiry).
- A merchant backend's token lasts **10 hours**; a person's token **5 minutes** (get a fresh one right before you call; an expired token gives `401`).

```bash
keycloak/get-access-token.sh merchant-api MARKETPLACE-5   # -> keycloak/output/jwt/MARKETPLACE-5.token   (merchant backend)
keycloak/get-access-token.sh user marketplace-5           # -> keycloak/output/jwt/marketplace-5.token   (merchant person)
keycloak/get-access-token.sh user seller-5-1              # -> keycloak/output/jwt/seller-5-1.token      (seller)
keycloak/get-access-token.sh user finance-ops             # -> keycloak/output/jwt/finance-ops.token     (staff)
keycloak/get-access-token.sh user backoffice-admin        # -> keycloak/output/jwt/backoffice-admin.token
```

## 2️⃣ Test the payment flow (as a merchant's backend)

Payments are created by the merchant's own backend, with its own credential: the token's `merchant_id` must be the
`merchantAccount` in the body (else `403`), and a merchant only finds its own payment intents (another merchant's is `404`).

```bash
keycloak/get-access-token.sh merchant-api MARKETPLACE-5
```

**Step 1: Create Payment Intent**

```bash
IDEMPOTENCY_KEY=$(printf '%08x-%04x-7%03x-8%03x-%04x%08x' $((RANDOM*RANDOM)) $((RANDOM)) $((RANDOM%4096)) $((RANDOM%4096)) $((RANDOM)) $((RANDOM*RANDOM)))
curl -i -X POST "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/payments" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/MARKETPLACE-5.token)" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -d '{
    "orderId": "ORDER-1450",
    "buyerId": "BUYER-1450",
    "merchantAccount": "MARKETPLACE-5",
    "processingModel": "MARKETPLACE",
    "totalAmount": { "quantity": 3000, "currency": "EUR" },
    "splits": [
      { "type": "BalanceAccount", "account": "SELLER-5-1", "amount": { "quantity": 1320, "currency": "EUR" }},
      { "type": "Commission", "amount": { "quantity": 180, "currency": "EUR" }},
      { "type": "BalanceAccount", "account": "SELLER-5-2", "amount": { "quantity": 1320, "currency": "EUR" }},
      { "type": "Commission", "amount": { "quantity": 180, "currency": "EUR" }}
    ]
  }'
```

**Expected Response (201 Created):**
```json
{
  "paymentIntentId": "pi_AcqzYyHCcAA",
  "clientSecret": "pi_3...._secret_....",
  "status": "CREATED"
}
```

> **Idempotency-Key**: required on every create. Use the same key for retries of the same request; the line above generates a new UUIDv7 each time.

**Step 2: Authorize Payment Intent**

Put the `paymentIntentId` from Step 1 into the URL. The body carries a Stripe test card token (`pm_card_visa`):

```bash
curl -i -X POST "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/payments/pi_AcqzYyHCcAA/authorize" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/MARKETPLACE-5.token)" \
  -d '{"paymentMethod":{"type":"CardToken","token":"pm_card_visa"}}'
```

| Token in the body | Result |
|---|---|
| `pm_card_visa` | `200`, `"status": "AUTHORIZED"` |
| `pm_card_chargeDeclined` | `200`, `"status": "DECLINED"` |
| no token (`-d '{}'`) | `422`, `"status": "FAILED"` (Stripe needs a card) |

**Read the payment intent** (`payment:read`):
```bash
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/payments/pi_AcqzYyHCcAA" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/MARKETPLACE-5.token)"
```

| Case | Result |
|---|---|
| body `merchantAccount` is not the token's merchant | `403`, nothing stored |
| another merchant's payment intent (read or authorize) | `404` |
| token without `merchant_id` | `403` |

### Option B: Using the Checkout Demo Page

A developer page (`checkout-demo/`) for the same flow in the browser (sections 0 and 1 done). Its server plays the
backend of the merchant selected on the page, with that merchant's client `merchant-api-<merchant>`.

```bash
cd checkout-demo
npm install         # once
npm run setup-env   # the cluster's addresses and the merchants' credentials into .env
npm run dev         # proxy on 3001, page on http://localhost:3000
```
Open `http://localhost:3000`. Mock mode, scenarios and the card step: [`checkout-demo/README.md`](../checkout-demo/README.md).

## 3️⃣ Test the balance API

Each endpoint has one kind of caller: `/balances/sellers/me` a seller, `/balances/merchants/me…` a merchant (both from
the token), the others staff (support / finance / admin), who name the merchant or seller in the path. Amounts are in cents.
Permission: `balance:read`.

| Call | Merchant (backend or person) | Seller | Staff (support / finance / admin) |
|---|---|---|---|
| `GET /balances/merchants/me` | its own: direct + commission payable and the total | `403` | `403` (no own balance) |
| `GET /balances/sellers/me` | `403` | its own | `403` (no own balance) |
| `GET /balances/merchants/me/sellers?page=&size=` | its sellers, paged | `403` | `403` |
| `GET /balances/merchants/me/sellers/{sellerId}` | one of its sellers; another merchant's or unknown → `404` | `403` | `403` |
| `GET /balances/merchants/{merchantAccount}/sellers?page=&size=` | `403` | `403` | the sellers of that merchant, paged |
| `GET /balances/sellers/{sellerId}` | `403` | `403` | any seller |

**A seller's own balance** (person token, 5 minutes: get a fresh one first):
```bash
keycloak/get-access-token.sh user seller-5-1
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/sellers/me" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/seller-5-1.token)"
```

**A merchant's own balance:**
```bash
keycloak/get-access-token.sh merchant-api MARKETPLACE-5
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/merchants/me" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/MARKETPLACE-5.token)"
```

**A merchant's sellers, paged** (each item has a `detailUrl` to that seller's balance):
```bash
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/merchants/me/sellers?page=0&size=5" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/MARKETPLACE-5.token)"
```

**A merchant reads one of its sellers (`200`) and another merchant's seller (`404`):**
```bash
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/merchants/me/sellers/SELLER-5-1" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/MARKETPLACE-5.token)"
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/merchants/me/sellers/SELLER-1-1" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/MARKETPLACE-5.token)"
```

**Finance reads any seller, and a merchant's sellers:**
```bash
keycloak/get-access-token.sh user finance-ops
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/sellers/SELLER-1-1" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/finance-ops.token)"
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/merchants/MARKETPLACE-1/sellers" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/finance-ops.token)"
```

Example response (merchant), after the section 2 marketplace payment (commission 180 + 180, minus our fee
€0.50 + 5% of €30.00 = 200):
```json
{"ownerType":"MERCHANT","ownerId":"MARKETPLACE-5","currency":"EUR","total":160,
 "accounts":[{"accountType":"MERCHANT_DIRECT_PAYABLE","accountCode":"MERCHANT_DIRECT_PAYABLE.MARKETPLACE-5.EUR","balance":0},
             {"accountType":"MERCHANT_COMMISSION_PAYABLE","accountCode":"MERCHANT_COMMISSION_PAYABLE.MARKETPLACE-5.EUR","balance":160}]}
```
Errors: no token `401`; no `balance:read`, or an endpoint for another kind of caller (see the table) → `403`; another merchant's or unknown seller `404` (`"code":"NOT_FOUND"`).

## 4️⃣ Test the transactions API (the back office's payment list)

One row per authorized payment, with its status (AUTHORIZED / CAPTURED / SETTLED). Permission: `transaction:read`.
Same convention as balances: a merchant uses `/transactions/merchants/me…` (its own, from the token); staff use
`/transactions/merchants/{merchantAccount}…` (the merchant they name). A payment is found only under its own merchant
(otherwise `404`). Each calls only its own endpoints (the other's → `403`).

**A merchant's transactions, newest first, paged** (filters: `orderId`, `paymentId`, `sellerId`, `status`, `from`, `to`; each item has a `detailUrl`):
```bash
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/transactions/merchants/me?page=0&size=20" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/MARKETPLACE-5.token)"
```

**One transaction** (buyer, PSP reference, splits). Use a `paymentId` from the list:
```bash
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/transactions/merchants/me/<paymentId>" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/MARKETPLACE-5.token)"
```

**Finance, one merchant's transactions and one of its payments:**
```bash
keycloak/get-access-token.sh user finance-ops
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/transactions/merchants/MARKETPLACE-5" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/finance-ops.token)"
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/transactions/merchants/MARKETPLACE-5/<paymentId>" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/finance-ops.token)"
```

## 🖥️ The back office (`mor-backoffice`)

The same balances and transactions as sections 3 and 4, as web screens, for sellers, merchant users and staff, with
Keycloak's login page (`http://keycloak.payment.svc.cluster.local:8080/…`, resolved on the Mac by OrbStack):
```bash
cd mor-backoffice
npm install   # once
npm run dev   # http://localhost:3100
```
Log in as any user from section 1 (e.g. `marketplace-5` / `merchant123`). Details: [`mor-backoffice/README.md`](../mor-backoffice/README.md).

## 5️⃣ Create a merchant account (ADMIN)

One request creates a merchant, its sellers, and all their ledger accounts. It is asynchronous: the answer is
`202 Accepted` and the accounts exist a moment later. Needs `account:write` (role `ADMIN`; person token, 5 minutes).

```bash
keycloak/get-access-token.sh user backoffice-admin
curl -i -X POST "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/accounts" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(cat keycloak/output/jwt/backoffice-admin.token)" \
  -d '{
    "merchantAccountCode": "MARKETPLACE-9",
    "legalName": "Marketplace Nine B.V.",
    "address": { "line1": "Damrak 9", "city": "Amsterdam", "postalCode": "1012 LG", "country": "NL" },
    "industry": "5399",
    "currency": "EUR",
    "platformFeeFixed": 50,
    "platformFeeBps": 0,
    "isAutoCaptured": true,
    "isAutoSettled": false,
    "sellerAccountCodes": ["SELLER-9-1", "SELLER-9-2"]
  }'
```

| Case | Result |
|---|---|
| valid request, admin | `202`, `"status": "ACCEPTED"`; a moment later the merchant, its 6 ledger accounts, each seller and its `SELLER_PAYABLE` exist |
| invalid field or rule (bad currency, code with `.`, same seller twice) | `400`, `"code": "VALIDATION_ERROR"`; nothing is queued |
| no `account:write` (support, finance, merchant) / no token | `403` / `401` |
| merchant code already exists | still `202`; creation is idempotent, so nothing is written |
| a seller code that belongs to another merchant | still `202`; the creation fails in the consumer and the request lands in `account.creation.requested.DLQ` |

`isAutoCaptured` defaults to `true` and `isAutoSettled` to `false` when omitted. A new merchant has **no Keycloak
login yet**: Keycloak holds only the seed merchants (`merchants-seed.json`); provisioning logins on account creation is a later step.

## Later: update one service on a running cluster

Only when you changed one service's code and the cluster is already running (not part of a fresh start).

1. Build and push that one image (same `latest` tag):
   ```bash
   infra/scripts/build-and-push-local.sh payment-consumers dcaglar1987 latest
   ```
2. Restart it. The tag did not change, so Kubernetes would keep running the old image; a restarted pod pulls `latest` again
   (`imagePullPolicy: Always`):

   | Service | Restart |
   |---|---|
   | payment-service | `kubectl rollout restart statefulset/payment-edge-cell -n payment` |
   | payment-edge-workers | `kubectl rollout restart statefulset/payment-edge-workers -n payment` |
   | payment-central-relay | `kubectl rollout restart deployment/payment-central-relay -n payment` |
   | payment-consumers | `kubectl rollout restart statefulset/payment-consumers -n payment` |

A restart does not change the databases. If the change touches a database schema (a Liquibase changelog under
`charts/*/db`), start fresh instead: reset the cluster and run sections 0 and 1 again.

**Test Organization:**
- **Unit Tests** (`*Test.kt`): Use mocks only, no external dependencies
  - **Execution**: Run with `mvn test` (Maven Surefire plugin, `test` phase)
  - **Design**: Fast tests run on every build for quick feedback
  - **Configuration**: Surefire excludes `*IntegrationTest.kt` files by filename pattern
- **Integration Tests** (`*IntegrationTest.kt`): Use real external dependencies via TestContainers, all tagged with `@Tag("integration")`
  - **Execution**: Run with `mvn verify` (Maven Failsafe plugin, `integration-test` + `verify` phases)
  - **Design**: Slower tests run before release/deployment for comprehensive validation
  - **Configuration**: Failsafe includes `*IntegrationTest.kt` files by filename pattern
  - **Lifecycle Separation**: Surefire and Failsafe complement each other - unit tests provide fast feedback, integration tests provide comprehensive validation before releases

For deep architecture or flow diagrams, see:
- [`architecture/architecture.md`](./architecture/architecture.md)
- [`README.md`](../README.md)

---