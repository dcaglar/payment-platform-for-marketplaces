import { Link, useParams, useSearchParams } from 'react-router-dom';
import { merchantApi, useApi } from '../../api';
import { Pager } from '../../components/Pager';
import { Status } from '../../components/Status';
import { money } from '../../format';
import type { Balance, Page } from '../../types';

const PAGE_SIZE = 20;

/**
 * Menu "Balances": what we owe the merchant on top (direct sales, marketplace commission, total), then its sellers
 * with their balances, paginated, in natural order. Route /merchants/:merchant/balances (merchant = "me" or a code).
 */
export function BalancesPage() {
  const { merchant = 'me' } = useParams();
  const [search, setSearch] = useSearchParams();
  const page = Number(search.get('page') ?? 0);
  const balance = useApi<Balance>(merchantApi.balance(merchant));
  const sellers = useApi<Page<Balance>>(merchantApi.sellers(merchant, page, PAGE_SIZE));

  let direct = 0;
  let commission = 0;
  if (balance.data) {
    for (const account of balance.data.accounts) {
      if (account.accountType === 'MERCHANT_DIRECT_PAYABLE') {
        direct += account.balance;
      } else if (account.accountType === 'MERCHANT_COMMISSION_PAYABLE') {
        commission += account.balance;
      }
    }
  }

  return (
    <>
      <h1>Balances</h1>
      <Status loading={balance.loading} error={balance.error} />
      {balance.data && (
        <>
          <p className="muted">
            What we owe {balance.data.ownerId} now ({balance.data.currency})
          </p>
          <div className="tiles">
            <div className="tile direct">
              <span>Direct sales</span>
              <strong>{money(direct, balance.data.currency)}</strong>
            </div>
            <div className="tile commission">
              <span>Marketplace commission</span>
              <strong>{money(commission, balance.data.currency)}</strong>
            </div>
            <div className="tile total">
              <span>Total</span>
              <strong>{money(balance.data.total, balance.data.currency)}</strong>
            </div>
          </div>
        </>
      )}

      <h2>Sellers</h2>
      <Status loading={sellers.loading} error={sellers.error} />
      {sellers.data && (
        <>
          <table className="list">
            <thead>
              <tr>
                <th>Seller</th>
                <th className="amount">Balance</th>
              </tr>
            </thead>
            <tbody>
              {sellers.data.items.map((seller) => (
                <tr key={seller.ownerId}>
                  <td>
                    <Link to={`/merchants/${encodeURIComponent(merchant)}/sellers/${encodeURIComponent(seller.ownerId)}`}>
                      {seller.ownerId}
                    </Link>
                  </td>
                  <td className="amount">{money(seller.total, seller.currency)}</td>
                </tr>
              ))}
              {sellers.data.items.length === 0 && (
                <tr>
                  <td colSpan={2} className="muted">
                    No sellers.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          <Pager page={sellers.data} onPage={(next) => setSearch({ page: String(next) })} />
        </>
      )}
    </>
  );
}
