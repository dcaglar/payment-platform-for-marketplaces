import type { ReactNode } from 'react';
import { Link, NavLink, useMatch } from 'react-router-dom';
import { callerOf, can, useMe } from './session';

/**
 * The frame of every page: the menu on the left (Transactions, Balances), who is logged in and logout on top.
 * A merchant user's menu is its own (/merchants/me/…); staff get the menu of the merchant they opened (/merchants/{code}/…).
 * A seller has only its balance, so no menu.
 */
export function Layout({ children }: { children: ReactNode }) {
  const me = useMe();
  const caller = callerOf(me);
  const opened = useMatch('/merchants/:merchant/*');
  let merchant: string | null = null;
  if (caller === 'merchant') {
    merchant = 'me';
  } else if (caller === 'staff' && opened !== null && opened.params.merchant) {
    merchant = opened.params.merchant;
  }

  return (
    <div className="frame">
      <aside>
        <div className="brand-name">MoR back office</div>
        {caller === 'staff' && (
          <>
            <Link className="merchant-switch" to="/">
              {merchant === null ? 'Choose a merchant' : `${merchant} · change`}
            </Link>
          </>
        )}
        {merchant !== null && (
          <nav>
            {can(me, 'transaction:read') && (
              <NavLink to={`/merchants/${encodeURIComponent(merchant)}/transactions`}>Transactions</NavLink>
            )}
            {can(me, 'balance:read') && (
              <NavLink to={`/merchants/${encodeURIComponent(merchant)}/balances`}>Balances</NavLink>
            )}
          </nav>
        )}
      </aside>
      <div className="content">
        <header>
          <span>
            {me.username} · {caller.toUpperCase()}
            {me.merchantId && ` · ${me.merchantId}`}
            {me.sellerId && ` · ${me.sellerId}`}
          </span>
          <a href="/auth/logout">Log out</a>
        </header>
        <main>{children}</main>
      </div>
    </div>
  );
}
