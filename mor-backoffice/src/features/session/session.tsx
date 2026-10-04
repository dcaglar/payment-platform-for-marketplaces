import { createContext, useContext, type ReactNode } from 'react';
import { useApi } from '../../api';
import type { Me } from '../../types';

/**
 * Which screens a person gets, from the token's claims and permissions (the same rules the API applies):
 * staff (merchant:all) every merchant; a merchant user (merchant_id) its own; a seller (seller_id) its balance.
 */
export type Caller = 'staff' | 'merchant' | 'seller' | 'none';

export function callerOf(me: Me): Caller {
  if (me.permissions.includes('merchant:all')) {
    return 'staff';
  }
  if (me.merchantId !== null) {
    return 'merchant';
  }
  if (me.sellerId !== null) {
    return 'seller';
  }
  return 'none';
}

export function can(me: Me, permission: string): boolean {
  return me.permissions.includes(permission);
}

const SessionContext = createContext<Me | null>(null);

/** The logged-in person; only used below SessionProvider, where someone is logged in. */
export function useMe(): Me {
  const me = useContext(SessionContext);
  if (me === null) {
    throw new Error('useMe outside a session');
  }
  return me;
}

/** Asks the server who is logged in; shows the login page when nobody is. */
export function SessionProvider({ loggedOut, children }: { loggedOut: ReactNode; children: ReactNode }) {
  const { data, error, loading } = useApi<Me>('/auth/me');
  if (loading) {
    return <p className="muted">Loading…</p>;
  }
  if (error !== null && error.status === 401) {
    return <>{loggedOut}</>;
  }
  if (error !== null || data === null) {
    return <p className="error">The back office server is not reachable: {error?.message}</p>;
  }
  return <SessionContext.Provider value={data}>{children}</SessionContext.Provider>;
}
