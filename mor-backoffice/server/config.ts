import { execFile } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { promisify } from 'node:util';

const run = promisify(execFile);

/** Where the back office itself is reached in the browser (Vite on 3100 forwards /auth and /api to this server). */
export const PUBLIC_URL = process.env.PUBLIC_URL || 'http://localhost:3100';
export const SERVER_PORT = Number(process.env.SERVER_PORT || 3101);

/**
 * Keycloak by the name it calls itself (KC_HOSTNAME in infra/helm-values/keycloak-values-local.yaml): its login page and
 * token issuer use it. The full service name resolves on the Mac through OrbStack, so the browser can open the login page.
 */
export const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://keycloak.payment.svc.cluster.local:8080';
export const REALM = process.env.KEYCLOAK_REALM || 'ecommerce-platform';
/** The people's client (public, PKCE): sellers, merchant users and staff log in through it. */
export const CLIENT_ID = process.env.KEYCLOAK_CLIENT_ID || 'backoffice-ui';

/** Signs the session cookie. A new one per start logs everybody out on restart, which is fine locally. */
export const SESSION_SECRET = process.env.SESSION_SECRET || randomBytes(32).toString('hex');

// The payment API (payment-consumers behind the ingress). Found like checkout-demo and the how-to-start curls:
// the ingress controller's LoadBalancer IP, cached for a minute so a rebuilt cluster needs no restart.
const CACHE_MS = 60_000;
let apiBaseUrl: string | null = null;
let apiBaseUrlAt = 0;

export async function paymentApiBaseUrl(): Promise<string> {
  if (process.env.PAYMENT_API_BASE_URL) {
    return process.env.PAYMENT_API_BASE_URL;
  }
  if (apiBaseUrl !== null && Date.now() - apiBaseUrlAt < CACHE_MS) {
    return apiBaseUrl;
  }
  const { stdout } = await run(
    'kubectl',
    ['get', 'svc', '-n', 'ingress-controller', 'ingress-nginx-controller', '-o', 'jsonpath={.status.loadBalancer.ingress[0].ip}'],
    { timeout: 3000 }
  );
  const ip = stdout.trim();
  if (ip === '') {
    throw new Error('No LoadBalancer IP for ingress-nginx-controller (is the cluster up?); or set PAYMENT_API_BASE_URL');
  }
  apiBaseUrl = `http://${ip}`;
  apiBaseUrlAt = Date.now();
  return apiBaseUrl;
}
