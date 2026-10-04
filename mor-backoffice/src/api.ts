import { useEffect, useState } from 'react';

/** The API's own error body (status, code, message) or a plain message. */
export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

/** GET from the back office's server (same origin, session cookie). 401 = the session is gone: log in again. */
export async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Accept: 'application/json' } });
  if (response.status === 401 && path.startsWith('/api/')) {
    window.location.assign('/auth/login');
    throw new ApiError(401, 'Logging in again');
  }
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body.message) {
        message = body.message;
      }
    } catch {
      // no JSON body: keep the status line
    }
    throw new ApiError(response.status, message);
  }
  return response.json() as Promise<T>;
}

export interface Loaded<T> {
  data: T | null;
  error: ApiError | null;
  loading: boolean;
}

/** Loads a path and reloads when it changes. */
export function useApi<T>(path: string): Loaded<T> {
  const [state, setState] = useState<Loaded<T>>({ data: null, error: null, loading: true });
  useEffect(() => {
    let current = true;
    setState({ data: null, error: null, loading: true });
    getJson<T>(path)
      .then((data) => {
        if (current) {
          setState({ data, error: null, loading: false });
        }
      })
      .catch((error: ApiError) => {
        if (current) {
          setState({ data: null, error, loading: false });
        }
      });
    return () => {
      current = false;
    };
  }, [path]);
  return state;
}

/**
 * The API's URLs for one merchant's data. [merchant] is "me" for a merchant user (the API takes it from the token)
 * or a merchant code for staff: the same convention as the API (/<api>/merchants/me… or /<api>/merchants/{code}…).
 */
export const merchantApi = {
  balance(merchant: string): string {
    return `/api/v1/balances/merchants/${encodeURIComponent(merchant)}`;
  },
  sellers(merchant: string, page: number, size: number): string {
    return `/api/v1/balances/merchants/${encodeURIComponent(merchant)}/sellers?page=${page}&size=${size}`;
  },
  seller(merchant: string, sellerId: string): string {
    // staff read any seller by id; a merchant only its own, under merchants/me
    if (merchant === 'me') {
      return `/api/v1/balances/merchants/me/sellers/${encodeURIComponent(sellerId)}`;
    }
    return `/api/v1/balances/sellers/${encodeURIComponent(sellerId)}`;
  },
  transactions(merchant: string, query: URLSearchParams): string {
    return `/api/v1/transactions/merchants/${encodeURIComponent(merchant)}?${query.toString()}`;
  },
  transaction(merchant: string, paymentId: string): string {
    return `/api/v1/transactions/merchants/${encodeURIComponent(merchant)}/${encodeURIComponent(paymentId)}`;
  },
};

/** Finance only (ledger:read + merchant:all): a payment's txs and journal entries. Staff name the merchant. */
export const txApi = {
  payment(merchant: string, paymentId: string): string {
    return `/api/v1/txs/merchants/${encodeURIComponent(merchant)}/payments/${encodeURIComponent(paymentId)}`;
  },
};
