import { Link, useParams } from 'react-router-dom';
import { merchantApi, useApi } from '../../api';
import { Status } from '../../components/Status';
import type { Balance } from '../../types';
import { BalanceCard } from './BalanceCard';

/** One seller's balance. Route /merchants/:merchant/sellers/:sellerId. */
export function SellerBalancePage() {
  const { merchant = 'me', sellerId = '' } = useParams();
  const { data, error, loading } = useApi<Balance>(merchantApi.seller(merchant, sellerId));
  return (
    <>
      <p>
        <Link to={`/merchants/${encodeURIComponent(merchant)}/balances`}>← Balances</Link>
      </p>
      <h1>Seller {sellerId}</h1>
      <Status loading={loading} error={error} />
      {data && <BalanceCard balance={data} />}
    </>
  );
}
