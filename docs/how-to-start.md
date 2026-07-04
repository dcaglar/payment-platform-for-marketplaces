# 🚀 How to Start

Follow these steps to provision auth, get a token, test the API, and run load tests locally.

This is a short, practical “start order” for spinning up the stack on OrbStack native Kubernetes, with one‑line notes on what each script does.

Prereqs (once):
- OrbStack native Kubernetes, kubectl, helm installed
- Troubleshooting connectivity? See docs/troubleshooting/connectivity.md

## ⚠️ The Golden Rules (Immutable Constraints)

When working on or extending this system, the following rules are absolute and must never be violated:

1. **The Separation of Powers (Consumers vs. Relays):**
   - **OutboxRelayJob**: The only component allowed to publish to Kafka. It reads the outbox table and routes messages. It must never update operational database state.
   - **Consumers (e.g., PspResultConsumer, CaptureCommandExecutor)**: The only components allowed to mutate the core database and ledger. They must never publish to Kafka directly. They communicate their results by appending new events to the Outbox.

2. **No Kafka Transactions:**
   - We do not use Kafka exactly-once semantics (EOS) or Kafka transactions. Instead, we rely purely on the Two-Stage Outbox Pattern to guarantee message durability and linearizability.

3. **Stateless Network Workers:**
   - Any worker interacting with the outside world (e.g., `CaptureCommandExecutor` calling the PSP) must do exactly one thing: execute the network call and append the result to the Outbox. It must not touch the ledger or alter core Payment domains.

---
 LOCAL ORB CLUSTER
## 0️⃣ Start the infrastructure and services

Pre-step: switch to the project root directory and make scripts executable
```bash
cd /path/to/ecommerce-platform-kotlin
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
API_BASE_URL=$(kubectl get svc ingress-nginx-controller -n ingress-nginx -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "Using Idempotency-Key=$(IDEMPOTENCY_KEY) base url used :${API_BASE_URL}" \
curl -i -X POST "http://${API_BASE_URL}/api/v1/payments" \  
   -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(cat ./keycloak/output/jwt/payment-service.token)" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
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
  
  
  
IDEMPOTENCY_KEY=$(printf '%08x-%04x-7%03x-8%03x-%04x%08x' $((RANDOM*RANDOM)) $((RANDOM)) $((RANDOM%4096)) $((RANDOM%4096)) $((RANDOM)) $((RANDOM*RANDOM)))
API_ENDPOINT="http://$(kubectl get svc ingress-nginx-controller -n ingress-nginx -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/payments"
echo "Using Idempotency-Key=${IDEMPOTENCY_KEY}"
echo "Using base url: http://${API_ENDPOINT}"
curl -i -X POST "${API_ENDPOINT}" \
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
AUTHORIZATION_ENDPOINT="http://$(kubectl get svc ingress-nginx-controller -n ingress-nginx -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/api/v1/payments/pi_AsON4dLCAAA/authorize"
curl -i -X POST "$AUTHORIZATION_ENDPOINT" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(cat ./keycloak/output/jwt/payment-service.token)" \
  -d '{}'
```

> **Production Flow**: In the actual checkout flow, Stripe Payment Element collects payment details client-side and attaches the payment method to the PaymentIntent. The backend then confirms the payment using the stored PaymentIntent ID without receiving any card data.

### Option B: Using Checkout Demo Page (Interactive UI)

A developer-friendly web interface for testing the complete end-to-end payment flow with Stripe Payment Element.

**Prerequisites:**
- Node.js 18+ installed
- Steps 1-9 completed (infrastructure, Keycloak provisioning)
- Stripe account (for Stripe publishable key)

**Setup:**

1. Install dependencies:
```bash
cd checkout-demo
npm install
```

2. Generate environment configuration:
```bash
# Make sure you've run step 9 (provision-keycloak.sh) first
npm run setup-env
```

This automatically:
- Reads client secret from `keycloak/output/secrets.txt`
- Reads API endpoints from `infra/endpoints.json`
- Creates `.env` file with all configuration

3. Configure Stripe publishable key:
```bash
# Add your Stripe publishable key to .env file
echo "VITE_STRIPE_PUBLISHABLE_KEY=pk_test_your_key_here" >> checkout-demo/.env
```

4. Start the demo:
```bash
npm run dev
```

This starts both:
- Frontend server at `http://localhost:3000` (Vite)
- Backend proxy server at `http://localhost:3001` (simulates order-service/checkout-service)

The app will automatically open at `http://localhost:3000`

**Usage:**

The payment flow follows the complete end-to-end Stripe Payment Element flow:

1. **Fill Order Details:**
    - Order ID (e.g., `ORDER-TEST-001`)
    - Buyer ID (e.g., `BUYER-123`)
    - Total amount (in smallest currency unit, e.g., cents)
    - Select currency
    - Add one or more payment splits with target entity IDs and amounts
    - Click "Proceed to Checkout"

2. **Payment Creation:**
    - Frontend calls your backend to create payment
    - Backend creates payment record and calls Stripe to create PaymentIntent
    - Backend returns `paymentIntentId` and `clientSecret`
    - If Stripe call is pending (202), frontend polls for client secret

3. **Payment Details Collection (Stripe Payment Element):**
    - Stripe Payment Element is initialized with `clientSecret`
    - Shopper enters card details in Payment Element
    - **Card data goes directly to Stripe** (never touches your servers)
    - Click "Pay Now" to submit payment details to Stripe

4. **Payment Authorization:**
    - Frontend calls authorize endpoint with payment ID only
    - **No payment details are sent** - backend uses stored PaymentIntent ID
    - Backend confirms payment with Stripe
    - Frontend displays success/error result

The backend proxy automatically handles token acquisition and payment-service calls - no manual token management needed!

**Architecture:**

The checkout demo uses a production-like flow with Stripe Payment Element:
- **Frontend** (React) → calls **Backend Proxy** (Node.js/Express) to create payment
- **Backend Proxy** → gets token from Keycloak (server-to-server)
- **Backend Proxy** → calls payment-service with token (server-to-server)
- **Frontend** ← receives `paymentIntentId` and `clientSecret`
- **Frontend** → initializes Stripe Payment Element with `clientSecret`
- **Shopper** → enters card details in Payment Element
- **Payment Element** → sends card data directly to Stripe (browser → Stripe, never touches your servers)
- **Frontend** → calls **Backend Proxy** to authorize payment (no payment details sent)
- **Backend Proxy** → calls payment-service authorize endpoint
- **Backend** → confirms payment with Stripe using stored PaymentIntent ID
- **Frontend** ← receives authorization result

**Data Flow:**
- **Card data**: Browser → Stripe (never touches your servers)
- **Order data**: Browser → Your Backend → Database
- **Payment control**: Browser → Your Backend → Stripe → Your Backend → Browser

> 💡 **Note**: The backend proxy simulates a production backend (order-service/checkout-service) and needs CORS enabled because the browser calls it directly (browser → proxy is cross-origin).




9) Observability stack (Elasticsearch/Logstash/Kibana) (Optional)
- What: Installs ELK stack for log aggregation and searching.
- Run:
```bash
infra/scripts/deploy-observability-stack.sh
```





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
- [`docs/architecture.md`](./architecture/architecture.md)
- [`README.md`](../README.md)

---