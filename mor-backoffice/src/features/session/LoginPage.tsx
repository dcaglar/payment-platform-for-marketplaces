/** Nobody is logged in: the login itself happens on Keycloak's page, started by the server. */
export function LoginPage() {
  return (
    <main className="login">
      <h1>MoR back office</h1>
      <p className="muted">Balances and transactions for sellers, merchants and our staff.</p>
      <a className="button" href="/auth/login">
        Log in
      </a>
    </main>
  );
}
