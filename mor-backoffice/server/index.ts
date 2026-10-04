import express, { type NextFunction, type Request, type Response } from 'express';
import session from 'express-session';
import { callback, currentAccessToken, login, logout, SESSION_COOKIE, whoIs } from './auth.js';
import { paymentApiBaseUrl, PUBLIC_URL, SERVER_PORT, SESSION_SECRET } from './config.js';

/**
 * The back office's own server ("backend for frontend"): it logs people in with Keycloak, keeps their tokens in the
 * session, and calls the payment API on their behalf. The page only talks to this server, with a session cookie.
 */
const app = express();

app.use(
  session({
    name: SESSION_COOKIE,
    secret: SESSION_SECRET,
    resave: false,
    saveUninitialized: false,
    // lax: the cookie still comes along when Keycloak sends the browser back to /auth/callback
    cookie: { httpOnly: true, sameSite: 'lax', secure: PUBLIC_URL.startsWith('https://') },
  })
);

// async route handlers: errors go to the error handler below instead of being lost
function handle(route: (req: Request, res: Response) => Promise<void>) {
  return (req: Request, res: Response, next: NextFunction) => {
    route(req, res).catch(next);
  };
}

app.get('/auth/login', handle(login));
app.get('/auth/callback', handle(callback));
app.get('/auth/logout', handle(logout));

/** GET /auth/me: who is logged in (401 = nobody). The page decides its screens from this. */
app.get(
  '/auth/me',
  handle(async (req, res) => {
    const accessToken = await currentAccessToken(req);
    if (accessToken === null) {
      res.status(401).json({ message: 'Not logged in' });
      return;
    }
    res.json(whoIs(accessToken));
  })
);

/**
 * GET /api/v1/...: passed on to the payment API with the user's token, path and query unchanged; the answer
 * (status, body) comes back as it is. The back office only reads, so only GET is let through.
 */
app.get(
  '/api/v1/*',
  handle(async (req, res) => {
    const accessToken = await currentAccessToken(req);
    if (accessToken === null) {
      res.status(401).json({ message: 'Not logged in' });
      return;
    }
    const upstream = await fetch((await paymentApiBaseUrl()) + req.originalUrl, {
      headers: { Authorization: `Bearer ${accessToken}`, Accept: 'application/json' },
      signal: AbortSignal.timeout(10_000),
    });
    res.status(upstream.status);
    res.type(upstream.headers.get('content-type') ?? 'application/json');
    res.send(await upstream.text());
  })
);

// one place that logs and answers errors (Keycloak or the API unreachable, a failed login)
app.use((error: Error, req: Request, res: Response, _next: NextFunction) => {
  console.error(`${req.method} ${req.originalUrl} failed:`, error);
  res.status(502).json({ message: error.message });
});

app.listen(SERVER_PORT, () => {
  console.log(`mor-backoffice server on http://localhost:${SERVER_PORT} (open the page at ${PUBLIC_URL})`);
});
