# Payment Checkout Demo

A developer page that sends create payment and authorize the way a checkout service would, so you can
try the payment flow and its idempotency behaviour without writing curl commands or handling tokens.

It runs in two ways:
- **Mock** (`npm run dev:mock`): no Keycloak, payment-service or Kubernetes needed
- **Local platform** (`npm run dev`): against the platform running in OrbStack

> ⚠️ Developer tool only, not for production.

## How it is built

| Part | Port | Role |
|---|---|---|
| React page (`src/`, Vite) | 3000 | The checkout form, Stripe's card form, the payment flow |
| Proxy (`server.js`) | 3001 | Plays the backend of the merchant selected on the page: gets that merchant's Keycloak token (`merchant-api-<merchant>`), calls payment-service, passes status codes and the `Retry-After`, `Location` and `Idempotent-Replayed` headers back (exposed via CORS) |
| Mock (`mock-server.js`) | 3001 | Replaces the proxy **and** payment-service for `dev:mock` |

### How the page uses the Idempotency-Key

One key (UUIDv7, `src/services/uuidv7.js`) per checkout attempt, kept in memory in `src/App.jsx`:

| When you click "Proceed to Checkout" | Key |
|---|---|
| No key yet, or the form content changed | New key |
| Same form content as the unfinished attempt | Same key |

| Answer from create payment | What the page does |
|---|---|
| `201` / `200` / `202` | Payment intent exists: continue (poll on `202`), drop the key |
| `409` (still processing) | Wait `Retry-After` seconds, send again with the same key |
| `422` (key used with a different body) | Show the error, drop the key |
| Network error, timeout, `5xx`, `400` | Show the error, keep the key: clicking again with the same form resends it |

The key lives only in the page's memory: reloading the page starts a new key.

### How the card step works

The page uses Stripe's "finalize payments on the server" flow: the card form runs in **deferred mode**
(publishable key, amount, currency, `captureMethod: 'manual'`, `paymentMethodCreation: 'manual'`).
"Pay Now" checks the card fields (`elements.submit()`), sends the card to **Stripe only**
(`stripe.createPaymentMethod`), and calls our authorize endpoint with the returned `pm_...` id.
payment-service then confirms its Stripe PaymentIntent with that payment method, server-side.
The card number never reaches our servers.

Which PSP decides the result depends on payment-service's `psp.gateway.type`:

| Where it runs | PSP | Does the card matter? |
|---|---|---|
| Local cluster (`deploy-all-local.sh`, chart `local/values.yaml`: `pspGatewayType: STRIPE`) | **Stripe test mode** | yes, see the table below |
| e2e tests, or `pspGatewayType: SIMULATED` | simulator | no: the scenario weights in `psp.authorization.simulation` decide (`NORMAL` approves everything) |

Stripe mode needs the Stripe **secret test key** as `STRIPE_API_KEY` in the SOPS-encrypted
`edge-cell-sops-secrets.yaml` (repo root). The local deploy passes it to payment-service through the
`edge-cell-credentials` Secret. To add or change it:

```bash
sops -i edge-cell-sops-secrets.yaml   # opens decrypted in your editor; add STRIPE_API_KEY: sk_test_...; save to re-encrypt
```

### Test cards (Stripe test mode)

Expiry: any future date · CVC: any 3 digits (4 for Amex) · postal code: any. The page shows the same list
below the card form, with a copy button.

| Card | Number | Stripe | Our API |
|---|---|---|---|
| Visa | `4242 4242 4242 4242` | approved (held, not captured) | 200 `AUTHORIZED` |
| Mastercard | `5555 5555 5555 4444` | approved | 200 `AUTHORIZED` |
| American Express | `3782 822463 10005` | approved | 200 `AUTHORIZED` |
| Generic decline | `4000 0000 0000 0002` | `generic_decline` | 200 `DECLINED` |
| Insufficient funds | `4000 0000 0000 9995` | `insufficient_funds` | 200 `DECLINED` |
| Lost card | `4000 0000 0000 9987` | `lost_card` | 200 `DECLINED` |
| Stolen card | `4000 0000 0000 9979` | `stolen_card` | 200 `DECLINED` |
| Expired card | `4000 0000 0000 0069` | expired card | 200 `DECLINED` |
| Incorrect CVC | `4000 0000 0000 0127` | incorrect CVC | 200 `DECLINED` |
| Processing error | `4000 0000 0000 0119` | processing error | 200 `DECLINED` |
| Blocked by Radar | `4100 0000 0000 0019` | highest fraud risk | 200 `DECLINED` |
| 3-D Secure required | `4000 0000 0000 3220` | `requires_action` | 202 `PENDING_AUTH`, stays there: this demo has no 3-D Secure step |
| 3-D Secure always | `4000 0027 6000 3184` | `requires_action` | 202 `PENDING_AUTH`, stays there |

Source: https://docs.stripe.com/testing. "Our API" follows `StripePspAuthorizationGatewayAdapter`: a card
decline is a result (`DECLINED`, 200), not an error.

## Setup (once)

```bash
cd checkout-demo
npm install
```

Add your Stripe **publishable** key to `.env` (needed for the card form in both modes):

```bash
echo "VITE_STRIPE_PUBLISHABLE_KEY=pk_test_your_key_here" >> .env
```

Never put a secret key (`sk_...`) in `.env`: every `VITE_` value ends up in the browser.

## Run without a backend (mock)

```bash
npm run dev:mock
```

The mock follows payment-service's idempotency rules (`IdempotencyService`):

| What you send | Mock answers |
|---|---|
| New key | `201`. If the Order ID contains `TIMEOUT`: `202`, and polling finds the client secret on the 3rd poll |
| Same key + same body while the first is still running | `409` + `Retry-After: 2` |
| Same key + same body after it finished | `200`, stored response replayed, `Idempotent-Replayed: true` |
| Same key + different body | `422` |

| Setting | Default | Effect |
|---|---|---|
| `MOCK_CREATE_DELAY_MS` | `1500` | How long a create stays `PENDING` |
| `MOCK_STRICT` | `true` | Validate like payment-service: UUIDv7 key, `merchantAccount`, `processingModel`, split rules. `false` skips it |

- See the keys and payment intents it created: `http://localhost:3001/__mock/state`
- Clear them: `curl -X POST http://localhost:3001/__mock/reset`

## Run against the local platform

Prerequisites: the platform is running and Keycloak is provisioned (`docs/how-to-start.md`, sections 0 and 1).

```bash
npm run setup-env
npm run dev
```

`setup-env` writes `.env`:
- the merchant backends' credentials (`MERCHANT_CREDENTIALS`)
- the Keycloak and payment API addresses, found with `kubectl` (load-balancer IPs of `keycloak` and
  `ingress-nginx-controller`, the same lookups as the curl commands in `docs/how-to-start.md`)
- it keeps `VITE_STRIPE_PUBLISHABLE_KEY`

Run it again after a redeploy: the addresses can change.

The proxy plays **the backend of the merchant selected on the page**: for each payment it gets a token for that
merchant's Keycloak client `merchant-api-<merchant>` (role `MERCHANT`, claim `merchant_id`) and calls the payment API
with it. `setup-env` writes the merchants it can act for into `.env` (`MERCHANT_CREDENTIALS`, all seed merchants from
`keycloak/realm/merchants-seed.json`). These are the local seed secrets (`docs/how-to-start.md`, section 1). A merchant without an entry gets `400`
("no credential"); a payment for another merchant than the token's gets `403` from the payment API.

The form is prefilled with the marketplace payment used by the e2e test (`MARKETPLACE-5`, 3000 EUR,
`SELLER-5-1` 1320, Commission 180, `SELLER-5-2` 1320, Commission 180: a 12% marketplace commission). `MARKETPLACE-5` is the
simulator target, so after "Pay Now" the payment runs on to `SETTLED`.
For a direct sale choose `DIRECT_MERCHANT` (no splits).

## Test scenarios

### Two requests with the same key at once (`409`), replay (`200`), reuse (`422`)

The page hides the form after the first click, so a double click sends only one request. Send the
requests with curl instead.

**Against the mock:**
```bash
KEY=$(node -e "import('./src/services/uuidv7.js').then(m => console.log(m.uuidV7()))")
BODY='{"orderId":"ORDER-IDEM-1","buyerId":"BUYER-1","merchantAccount":"MARKETPLACE-5","processingModel":"DIRECT_MERCHANT","totalAmount":{"quantity":3000,"currency":"EUR"}}'
send() { curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:3001/api/checkout/process-payment -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" -d "$1"; }
send "$BODY" & send "$BODY" & wait          # one 201, one 409
send "$BODY"                                # 200 (replayed)
send "${BODY/ORDER-IDEM-1/ORDER-IDEM-2}"    # 422
curl -s http://localhost:3001/__mock/state  # one payment intent
```

**Against the local platform** (from the project root):
```bash
./keycloak/get-access-token.sh merchant-api MARKETPLACE-5
API=$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
TOKEN=$(cat ./keycloak/output/jwt/MARKETPLACE-5.token)
KEY=$(printf '%08x-%04x-7%03x-8%03x-%04x%08x' $((RANDOM*RANDOM)) $((RANDOM)) $((RANDOM%4096)) $((RANDOM%4096)) $((RANDOM)) $((RANDOM*RANDOM)))
BODY='{"orderId":"ORDER-IDEM-1","buyerId":"BUYER-1","merchantAccount":"MARKETPLACE-5","processingModel":"DIRECT_MERCHANT","totalAmount":{"quantity":3000,"currency":"EUR"}}'
send() { curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://$API/api/v1/payments" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" -d "$1"; }
send "$BODY" & send "$BODY" & wait          # one 201, one 409
send "$BODY"                                # 200 (replayed)
send "${BODY/ORDER-IDEM-1/ORDER-IDEM-2}"    # 422
```
The simulator takes 300–500 ms to create a payment; if both parallel requests return `201`, the second
came too late, run it again with a new `KEY`. Check that only one payment intent exists:
```bash
kubectl exec -n payment payment-edge-cell-0 -c edge-db -- psql -U postgres -d edge-db -c \
  "SELECT status, payment_intent_id FROM idempotency_keys WHERE idempotency_key='$KEY';"
kubectl exec -n payment payment-edge-cell-0 -c edge-db -- psql -U postgres -d edge-db -c \
  "SELECT count(*) FROM payment_intents WHERE order_id='ORDER-IDEM-1';"
```

### Retry after an error (from the page, same key reused)

Run the page and the backend in separate terminals so you can stop only the backend:

| | Mock | Local platform |
|---|---|---|
| Terminal A | `npm run dev:client` | `npm run dev:client` |
| Terminal B | `npm run mock:server` | `npm run dev:server` |

Stop terminal B, click "Proceed to Checkout" (network error), start terminal B again, click again without
changing the form. The same key is sent and one payment intent is created (`201`).

### PSP timeout (`202`, then polling)

**Mock:** use an Order ID containing `TIMEOUT`.

**Local platform:** the simulator never times out by default (`timeouts.probability: 0`). Set it to 100
(the simulator then waits 10 s, create payment answers `202` after its 3 s limit):
```bash
kubectl set env statefulset/payment-edge-cell -n payment -c payment-service \
  SPRING_APPLICATION_JSON='{"psp":{"authorization":{"simulation":{"scenarios":{"NORMAL":{"timeouts":{"probability":100}}}}}}}'
```
The pod restarts. The page gets `202` and polls; after about 10 s the card form appears. Authorize goes
through the same simulator, so expect it to be slow too. Undo:
```bash
kubectl set env statefulset/payment-edge-cell -n payment -c payment-service SPRING_APPLICATION_JSON-
```
(`SPRING_APPLICATION_JSON` is used because a plain variable such as `PSP_..._NORMAL_...` would be bound
to a lowercased map key `normal`, not the `NORMAL` scenario.)

## Troubleshooting

| Problem | Check |
|---|---|
| `EADDRINUSE :3001` or `:3000` | An earlier `npm run dev` is still running: stop it (`lsof -nP -iTCP:3001 -sTCP:LISTEN` shows the PID) |
| "Cannot reach Keycloak" | Run `npm run setup-env` again; check `kubectl get svc keycloak -n payment` |
| Keycloak answers 404 for the realm | Keycloak is not set up: `./keycloak/setup-keycloak.sh`, then `npm run setup-env` |
| `400` with "Must be a valid UUIDv7 format" | The Idempotency-Key is not a UUIDv7 |
| `400` validation errors | MARKETPLACE: splits must add up to the total; DIRECT_MERCHANT: no splits; one currency |
| Card form does not appear | `VITE_STRIPE_PUBLISHABLE_KEY` missing in `.env`; restart `npm run dev` after changing `.env` |

The proxy logs every step (token, request, response) in its terminal.

## Development

- React 18 + Vite, plain JavaScript
- `npm run build` writes a production build to `dist/`
