import type { Request, Response } from 'express';
import * as oidc from 'openid-client';
import { CLIENT_ID, KEYCLOAK_URL, PUBLIC_URL, REALM } from './config.js';

/** What the session keeps of a login. Only this server sees the tokens; the browser has a session cookie. */
export interface Tokens {
  accessToken: string;
  refreshToken: string | undefined;
  idToken: string | undefined;
  expiresAt: number; // epoch ms
}

declare module 'express-session' {
  interface SessionData {
    tokens?: Tokens;
    login?: { codeVerifier: string; state: string };
  }
}

const CALLBACK_PATH = '/auth/callback';
export const SESSION_COOKIE = 'mor-backoffice.sid';
const RENEW_BEFORE_EXPIRY_MS = 30_000;

let configuration: oidc.Configuration | null = null;

/** Keycloak's OpenID Connect metadata, read once. Plain http is allowed: this is the local cluster. */
async function keycloak(): Promise<oidc.Configuration> {
  if (configuration === null) {
    configuration = await oidc.discovery(
      new URL(`${KEYCLOAK_URL}/realms/${REALM}`),
      CLIENT_ID,
      undefined,
      oidc.None(),
      { execute: [oidc.allowInsecureRequests] }
    );
  }
  return configuration;
}

function toTokens(response: oidc.TokenEndpointResponse, previous?: Tokens): Tokens {
  return {
    accessToken: response.access_token,
    refreshToken: response.refresh_token ?? previous?.refreshToken,
    idToken: response.id_token ?? previous?.idToken,
    expiresAt: Date.now() + (response.expires_in ?? 60) * 1000,
  };
}

/** GET /auth/login: off to Keycloak's login page (authorization code with PKCE). */
export async function login(req: Request, res: Response): Promise<void> {
  const codeVerifier = oidc.randomPKCECodeVerifier();
  const state = oidc.randomState();
  req.session.login = { codeVerifier, state };
  const url = oidc.buildAuthorizationUrl(await keycloak(), {
    redirect_uri: PUBLIC_URL + CALLBACK_PATH,
    scope: 'openid',
    code_challenge: await oidc.calculatePKCECodeChallenge(codeVerifier),
    code_challenge_method: 'S256',
    state,
  });
  res.redirect(url.href);
}

/** GET /auth/callback: Keycloak sends the browser back with a code; exchange it for tokens and start a fresh session. */
export async function callback(req: Request, res: Response): Promise<void> {
  const pending = req.session.login;
  if (!pending) {
    res.redirect('/');
    return;
  }
  const response = await oidc.authorizationCodeGrant(await keycloak(), new URL(req.originalUrl, PUBLIC_URL), {
    pkceCodeVerifier: pending.codeVerifier,
    expectedState: pending.state,
  });
  // a new session id after login, so an id handed out before login is worth nothing
  req.session.regenerate((error) => {
    if (error) {
      res.status(500).send('Could not start the session');
      return;
    }
    req.session.tokens = toTokens(response);
    res.redirect('/');
  });
}

/** GET /auth/logout: ends our session and the Keycloak session, then back to the start page. */
export async function logout(req: Request, res: Response): Promise<void> {
  const idToken = req.session.tokens?.idToken;
  const config = await keycloak();
  req.session.destroy(() => {
    res.clearCookie(SESSION_COOKIE);
    const parameters: Record<string, string> = { post_logout_redirect_uri: PUBLIC_URL };
    if (idToken) {
      parameters.id_token_hint = idToken;
    }
    res.redirect(oidc.buildEndSessionUrl(config, parameters).href);
  });
}

/** The session's access token, renewed with the refresh token shortly before it expires. Null = not logged in (any more). */
export async function currentAccessToken(req: Request): Promise<string | null> {
  const tokens = req.session.tokens;
  if (!tokens) {
    return null;
  }
  if (Date.now() < tokens.expiresAt - RENEW_BEFORE_EXPIRY_MS) {
    return tokens.accessToken;
  }
  if (!tokens.refreshToken) {
    delete req.session.tokens;
    return null;
  }
  try {
    const response = await oidc.refreshTokenGrant(await keycloak(), tokens.refreshToken);
    req.session.tokens = toTokens(response, tokens);
    return req.session.tokens.accessToken;
  } catch (error) {
    // refresh token expired or the Keycloak session ended: log in again
    console.warn('Token renewal failed, the user has to log in again:', (error as Error).message);
    delete req.session.tokens;
    return null;
  }
}

/** Who is logged in, read from the access token Keycloak just gave this server (the API checks it again on every call). */
export interface Me {
  username: string;
  merchantId: string | null;
  sellerId: string | null;
  permissions: string[];
}

export function whoIs(accessToken: string): Me {
  const payload = JSON.parse(Buffer.from(accessToken.split('.')[1], 'base64url').toString('utf8'));
  return {
    username: payload.preferred_username ?? '',
    merchantId: payload.merchant_id ?? null,
    sellerId: payload.seller_id ?? null,
    permissions: payload.realm_access?.roles ?? [],
  };
}
