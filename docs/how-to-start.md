# 🚀 How to Start

Follow these steps to provision auth, get a token, test the API, and run load tests locally.

This is a short, practical “start order” for spinning up the stack on OrbStack native Kubernetes, with one‑line notes on what each script does.

Prereqs (once):
- OrbStack native Kubernetes, kubectl, helm installed
- Troubleshooting connectivity? See docs/troubleshooting/connectivity.md

---
 LOCAL ORB CLUSTER
## 0️⃣ Start the infrastructure and services

Pre-step: switch to the project root directory and make scripts executable
```bash
cd /path/to/payment-platform-for-marketplaces
chmod +x infra/scripts/*.sh
```


1) Build and push the docker images of all payment platform service(also t§)  to remote image registry
- What: Builds the latest source code of payment platform servcices into remote Docker images
- Run:
```bash
infra/scripts/build-all-payment-platform-images-and-push.sh
```

2) Deploy all external infra(keycloak, redis, kafka)  to the local environment local or azure,those are 
```bash
infra/scripts/deploy-all-external-infra-local.sh
```  

3) Deploy all payment platform services  to  local cluster(e.g payment-edge-cell, payment-edge-worker, payment-central-relay, payment-consuemrs)
```bash
infra/scripts/deploy-payment-platform-services-local.sh 
```  


4) Monitoring stack (optional)(Prometheus + Grafana) (Optional)
- What: Installs kube-prometheus-stack into monitoring.
- Run:
```bash
infra/scripts/deploy-monitoring-stack.sh
```

5) Exporters (Kafka & Postgresql) (Only if monitoring installed)
- What: Exposes Kafka consumer lag, offsets, etc. for Prometheus.
- Run:
```bash
infra/scripts/deploy-single-infra.sh kafka-exporter local
infra/scripts/deploy-single-infra.sh postgresql-exporter) local
```







## 1️⃣ Create Realms,Authentication, and Roles for test user


1)  Provision Keycloak realm and clients
- What: Creates realm, role, and OIDC confidential clients; writes secrets to keycloak/output/secrets.txt.
- Run:
```bash
provision-keycloak.sh
```
2) Generate access tokens for different operations
- What: Get JWT tokens for different use cases; saves tokens under `keycloak/output/jwt`.
- Default validity is ~1 hour. Append a TTL (hours) as the final argument to keep a test token alive longer (e.g., `... 6` for 6 h).
- Reuse the saved token across requests until it expires; no need to regenerate for every call.

**Generate token For Payment Creation (Service Account with payment:write):**
```bash
get-token.sh
# Token saved to: keycloak/output/jwt/payment-service.token
# Claims saved to: keycloak/output/jwt/payment-service.claims.json

# Request a 6-hour token via CLI override:
get-token.sh http://keycloak.payment.svc.cluster.local:8080 6
```

Tokens are intentionally scoped to those roles so you can exercise each endpoint with the appropriate identity.

##   2️⃣ Test the payment flow


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

**Expected Response (200 OK):**
```json
{
  "paymentIntentId": "pi_AcqzYyHCcAA",
  "clientSecret": "pi_AcqzYyHCcAA_secret_xyz123",
  "status": "CREATED"
}
```

> **Note on Idempotency-Key**: The header is required for all payment intent creation requests. Use the same key for retries of the same payment request to ensure idempotent behavior. Generate a unique UUID for each new payment request.

**Step 2: Authorize Payment Intent**

> **Note**: In production, payment details are collected by Stripe Payment Element (browser → Stripe). The authorize endpoint doesn't require payment method details - it uses the stored PaymentIntent ID. For testing with curl, you can send an empty body or omit paymentMethod:

```bash
AUTHORIZATION_ENDPOINT="http://$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/payments/pi_AsYhFHsCAAA/authorize"
echo "Using auth url used :${AUTHORIZATION_ENDPOINT}"
curl -i -X POST "${AUTHORIZATION_ENDPOINT}" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(cat ./keycloak/output/jwt/payment-service.token)" \
  -d '{}'
```

> **Production Flow**: In the actual checkout flow, Stripe Payment Element collects payment details client-side and attaches the payment method to the PaymentIntent. The backend then confirms the payment using the stored PaymentIntent ID without receiving any card data.

### Option B: Using the Checkout Demo Page

A developer page (`checkout-demo/`) for the same flow in the browser. Against the local platform
(sections 0 and 1 done):

```bash
cd checkout-demo
npm install
npm run setup-env
npm run dev
```

Open `http://localhost:3000`. To run it without a backend (mock), to configure scenarios (PSP timeout,
same key twice, retry after an error), and for the card step, see [`checkout-demo/README.md`](../checkout-demo/README.md).


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
- **No Hanging Tests**: All MockK syntax issues resolved for reliable test execution
- **Type Inference Fixed**: Resolved MockK type inference issues in `OutboxRelayJobTest.kt` with explicit type hints and Jackson JSR310 module configuration

For deep architecture or flow diagrams, see:
- [`architecture/architecture.md`](./architecture/architecture.md)
- [`README.md`](../README.md)

---