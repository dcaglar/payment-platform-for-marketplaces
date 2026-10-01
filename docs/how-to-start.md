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
- After rebuilding only the `payment-consumers` image, restart it so it pulls the new `latest` image: `kubectl rollout restart statefulset/payment-consumers -n payment`.


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







## 1️⃣ Create realm, roles, clients and test users

1) Provision Keycloak
- What: creates the realm, the roles (`payment:write`, `FINANCE`, `ADMIN`, `SELLER`, `SELLER_API`, `MERCHANT`) and the clients; writes client secrets to `keycloak/output/secrets.txt`.
- For **every seller** in central-db (`account_directory`): a login user `seller-x-y` / `seller123` (role `SELLER`) and an API client `seller-api-SELLER-X-Y` (role `SELLER_API`), both with a `seller_id` claim.
- For **every merchant**: an API client `merchant-api-MARKETPLACE-N` (role `MERCHANT`, `merchant_id` claim).
- Run (the platform must be deployed first, the script reads central-db):
```bash
provision-keycloak.sh
```

2) Get tokens
- Each script saves the token under `keycloak/output/jwt/`. API-client tokens (`get-token.sh`, `get-token-seller-api.sh`, `get-token-merchant-api.sh`) are valid for **10 hours**. User-login tokens (`get-token-seller.sh`, `get-token-finance.sh`) are valid for only **5 minutes**: get a fresh one right before you call (an expired token gives `401`).
- Each script finds Keycloak by itself (no arguments needed).

| Who | Command | Token file | Use it for |
|---|---|---|---|
| checkout / order service (`payment:write`) | `get-token.sh` | `payment-service.token` | payments |
| back office (`FINANCE`) | `get-token-finance.sh` | `finance-finance-ops.token` | any seller's balance |
| seller user (`SELLER`) | `get-token-seller.sh seller-5-1` | `seller-SELLER-5-1.token` | own balance |
| seller API client (`SELLER_API`) | `get-token-seller-api.sh SELLER-5-1` | `seller-api-SELLER-5-1.token` | own balance |
| merchant API client (`MERCHANT`) | `get-token-merchant-api.sh MARKETPLACE-5` | `merchant-api-MARKETPLACE-5.token` | own balance, its sellers' balances |

## 2️⃣ Test the payment flow

**Step 1: Create Payment Intent**

```bash
IDEMPOTENCY_KEY=$(printf '%08x-%04x-7%03x-8%03x-%04x%08x' $((RANDOM*RANDOM)) $((RANDOM)) $((RANDOM%4096)) $((RANDOM%4096)) $((RANDOM)) $((RANDOM*RANDOM)))
API_BASE_URL=$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "Using Idempotency-Key=${IDEMPOTENCY_KEY} base url used :${API_BASE_URL}"
curl -i -X POST "http://${API_BASE_URL}/api/v1/payments" \
   -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(cat ./keycloak/output/jwt/payment-service.token)" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -d '{
    "orderId": "ORDER-1450",
    "buyerId": "BUYER-1450",
    "merchantAccount": "MARKETPLACE-5",
    "processingModel": "MARKETPLACE",
    "totalAmount": { "quantity": 3000, "currency": "EUR" },
    "splits": [
      { "type": "BalanceAccount", "account": "SELLER-5-1", "amount": { "quantity": 1400, "currency": "EUR" }},
      { "type": "Commission", "amount": { "quantity": 100, "currency": "EUR" }},
      { "type": "BalanceAccount", "account": "SELLER-5-2", "amount": { "quantity": 1400, "currency": "EUR" }},
      { "type": "Commission", "amount": { "quantity": 100, "currency": "EUR" }}
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

> **Note on Idempotency-Key**: The header is required for all payment intent creation requests. Use the same key for retries of the same payment request. The line above generates a new UUIDv7 each time.

**Step 2: Authorize Payment Intent**

Put the `paymentIntentId` from Step 1 into the URL. The body carries a Stripe test card token (`pm_card_visa`):

```bash
AUTHORIZATION_ENDPOINT="http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/payments/pi_AcqzYyHCcAA/authorize"
echo "Using auth url used :${AUTHORIZATION_ENDPOINT}"
curl -i -X POST "${AUTHORIZATION_ENDPOINT}" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(cat ./keycloak/output/jwt/payment-service.token)" \
  -d '{"paymentMethod":{"type":"CardToken","token":"pm_card_visa"}}'
```

| Token in the body | Result |
|---|---|
| `pm_card_visa` | `200`, `"status": "AUTHORIZED"` |
| `pm_card_chargeDeclined` | `200`, `"status": "DECLINED"` |
| no token (`-d '{}'`) | `422`, `"status": "FAILED"` (Stripe needs a card) |

### Option B: Using the Checkout Demo Page

A developer page (`checkout-demo/`) for the same flow in the browser. Against the local platform
(sections 0 and 1 done):

```bash
cd checkout-demo
npm install   # once
npm run dev
```
The demo's backend finds the Keycloak secret (`keycloak/output/secrets.txt`) and the Keycloak / API addresses by itself, so nothing needs updating after the cluster is rebuilt. Under the card form the page lists Stripe test cards.

Open `http://localhost:3000`. To run it without a backend (mock), to configure scenarios (PSP timeout,
same key twice, retry after an error), and for the card step, see [`checkout-demo/README.md`](../checkout-demo/README.md).

## 3️⃣ Test the balance API

Who you are comes from the token; amounts are in cents. Every command below runs on its own, from any folder inside the repo (base URL and token path are looked up inline).

| Call | Allowed for | Returns |
|---|---|---|
| `GET /balances/me` | seller (user or seller-api client), merchant | your own balance |
| `GET /balances/{sellerId}` | merchant: only its own sellers (else `403`); finance/admin: any seller | that seller's balance |

**A seller's own balance (seller user; user tokens last 5 minutes, so get a fresh one first):**
```bash
"$(git rev-parse --show-toplevel)"/keycloak/get-token-seller.sh seller-5-1
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/me" \
  -H "Authorization: Bearer $(cat "$(git rev-parse --show-toplevel)"/keycloak/output/jwt/seller-SELLER-5-1.token)"
```

**A seller's own balance (seller-api client):**
```bash
"$(git rev-parse --show-toplevel)"/keycloak/get-token-seller-api.sh SELLER-5-1
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/me" \
  -H "Authorization: Bearer $(cat "$(git rev-parse --show-toplevel)"/keycloak/output/jwt/seller-api-SELLER-5-1.token)"
```

**A merchant's own balance (direct-sales payable + marketplace commission payable, and the total):**
```bash
"$(git rev-parse --show-toplevel)"/keycloak/get-token-merchant-api.sh MARKETPLACE-5
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/me" \
  -H "Authorization: Bearer $(cat "$(git rev-parse --show-toplevel)"/keycloak/output/jwt/merchant-api-MARKETPLACE-5.token)"
```

**A merchant reads one of its sellers (`200`):**
```bash
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/SELLER-5-1" \
  -H "Authorization: Bearer $(cat "$(git rev-parse --show-toplevel)"/keycloak/output/jwt/merchant-api-MARKETPLACE-5.token)"
```

**A merchant reads another merchant's seller (`403`):**
```bash
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/SELLER-1-1" \
  -H "Authorization: Bearer $(cat "$(git rev-parse --show-toplevel)"/keycloak/output/jwt/merchant-api-MARKETPLACE-5.token)"
```

**Back office reads any seller (user token, 5 minutes: get a fresh one first):**
```bash
"$(git rev-parse --show-toplevel)"/keycloak/get-token-finance.sh
curl -i "http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/balances/SELLER-1-1" \
  -H "Authorization: Bearer $(cat "$(git rev-parse --show-toplevel)"/keycloak/output/jwt/finance-finance-ops.token)"
```

Example response (merchant):
```json
{"ownerType":"MERCHANT","ownerId":"MARKETPLACE-5","currency":"EUR","total":300,
 "accounts":[{"accountType":"MERCHANT_DIRECT_PAYABLE","accountCode":"MERCHANT_DIRECT_PAYABLE.MARKETPLACE-5.EUR","balance":0},
             {"accountType":"MERCHANT_COMMISSION_PAYABLE","accountCode":"MERCHANT_COMMISSION_PAYABLE.MARKETPLACE-5.EUR","balance":300}]}
```
Errors: no token `401`, wrong role or not your seller `403`, unknown seller `404` (`"code":"NOT_FOUND"`).

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