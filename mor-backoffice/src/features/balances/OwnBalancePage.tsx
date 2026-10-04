import { useApi } from '../../api';
import { Status } from '../../components/Status';
import type { Balance } from '../../types';
import { BalanceCard } from './BalanceCard';

/** The logged-in seller's or merchant's own balance (the API takes the owner from the token). */
export function OwnBalancePage({ of }: { of: 'seller' | 'merchant' }) {
  const path = of === 'seller' ? '/api/v1/balances/sellers/me' : '/api/v1/balances/merchants/me';
  const { data, error, loading } = useApi<Balance>(path);
  return (
    <>
      <h1>My balance</h1>
      <Status loading={loading} error={error} />
      {data && <BalanceCard balance={data} />}
    </>
  );
}
