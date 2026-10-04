# mor-backoffice

The back office of the MoR payment platform: sellers, merchant users and our staff log in and see balances and
transactions. It reads the balance and transaction APIs of payment-consumers (`/api/v1/balances/…`,
`/api/v1/transactions/…`).

## How it is built

| Part | Where | What |
|---|---|---|
| Page | `src/` (React, TypeScript, Vite on port 3100) | the screens; talks only to its own server, with a session cookie |
| Server | `server/` (Node, Express, port 3101) | logs people in with Keycloak, keeps their tokens in the session, calls the payment API for them |

The browser never holds a token. Login is Keycloak's own page (client `backoffice-ui`, authorization code with PKCE,
library `openid-client`); the server stores the tokens, renews the access token before it expires, and passes
`GET /api/v1/…` on to the payment API with the user's token. Vite forwards `/auth` and `/api` to the server, so page and
server share one origin.

| Server route | Does |
|---|---|
| `GET /auth/login` | redirects to Keycloak's login page |
| `GET /auth/callback` | Keycloak's way back: code → tokens, new session |
| `GET /auth/me` | who is logged in (`401` = nobody) |
| `GET /auth/logout` | ends the session and the Keycloak session |
| `GET /api/v1/…` | the payment API with the user's token; status and body passed back unchanged |

## Screens (by who logs in)

The API's rules decide; the page only shows what a person may use (`src/features/session/session.tsx`).

| Who | Screens |
|---|---|
| Seller (`seller_id`) | own balance |
| Merchant user (`merchant_id`) | own balance; its sellers (paged) → one seller; its transactions (paged, filters: order, seller, status) → detail with splits |
| Staff (`merchant:all`: support, finance, admin) | type a merchant code → that merchant's sellers and transactions, the same screens |

Screen URLs follow the API's convention: `/merchants/me/…` for a merchant user, `/merchants/{code}/…` for staff.
Code per feature: `src/features/{session,balances,transactions,merchants}`.

## Run it (against the local cluster)

1. The platform runs and Keycloak is set up (`docs/how-to-start.md`, sections 0 and 1).
2. Start (Keycloak's login page is `http://keycloak.payment.svc.cluster.local:8080/…`, which OrbStack resolves on the Mac):
   ```bash
   cd mor-backoffice
   npm install      # once
   npm run dev      # server on 3101, page on http://localhost:3100
   ```
3. Open http://localhost:3100 and log in, e.g. `marketplace-5` / `merchant123`, `seller-5-1` / `seller123`,
   `support-ops` / `support123` (all users: how-to-start, section 1).

The payment API is found with `kubectl` (the ingress controller's LoadBalancer IP), like checkout-demo. Settings
(environment variables): `PAYMENT_API_BASE_URL`, `KEYCLOAK_URL` (default `http://keycloak.payment.svc.cluster.local:8080`, the name Keycloak calls itself), `KEYCLOAK_REALM`,
`KEYCLOAK_CLIENT_ID` (default `backoffice-ui`), `PUBLIC_URL` (default `http://localhost:3100`), `SERVER_PORT`,
`SESSION_SECRET` (default: a new one per start, which logs everybody out on restart).

`npm run typecheck` checks page and server; `npm run build` also builds the page.
