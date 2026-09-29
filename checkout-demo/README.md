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
| Proxy (`server.js`) | 3001 | Plays the order/checkout service: gets a Keycloak token, calls payment-service, passes status codes and the `Retry-After`, `Location` and `Idempotent-Replayed` headers back (exposed via CORS) |
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

Locally, payment-service runs with `psp.gateway.type: SIMULATED` and returns a simulated client secret
(`sim_cs_...`); the mock does the same. Stripe does not know that secret, so the page shows Stripe's card
form in **deferred mode**: only the publishable key, the amount and the currency are needed. No Stripe
secret key and no Stripe PaymentIntent are involved. "Pay Now" checks the card fields
(`elements.submit()`) and calls our authorize endpoint.

Use a Stripe test card: `4242 4242 4242 4242`, any future date, any CVC.

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
- the client secret from `keycloak/output/secrets.txt`
- the Keycloak and payment API addresses, found with `kubectl` (load-balancer IPs of `keycloak` and
  `ingress-nginx-controller`, the same lookups as the curl commands in `docs/how-to-start.md`)
- it keeps `VITE_STRIPE_PUBLISHABLE_KEY`

Run it again after a redeploy or a new `provision-keycloak.sh`: the secret and the addresses can change.

The form is prefilled with the marketplace payment used by the e2e test (`MARKETPLACE-5`, 3000 EUR,
`SELLER-5-1` 1400, Commission 100, `SELLER-5-2` 1400, Commission 100). `MARKETPLACE-5` is the
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
./keycloak/get-token.sh
API=$(kubectl get svc ingress-nginx-controller -n ingress-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
TOKEN=$(cat ./keycloak/output/jwt/payment-service.token)
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
| Keycloak answers 404 for the realm | Keycloak is not provisioned: `./keycloak/provision-keycloak.sh`, then `npm run setup-env` |
| `400` with "Must be a valid UUIDv7 format" | The Idempotency-Key is not a UUIDv7 |
| `400` validation errors | MARKETPLACE: splits must add up to the total; DIRECT_MERCHANT: no splits; one currency |
| Card form does not appear | `VITE_STRIPE_PUBLISHABLE_KEY` missing in `.env`; restart `npm run dev` after changing `.env` |

The proxy logs every step (token, request, response) in its terminal.

## Development

- React 18 + Vite, plain JavaScript
- `npm run build` writes a production build to `dist/`
