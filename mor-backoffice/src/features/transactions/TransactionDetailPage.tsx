import { Link, useParams } from 'react-router-dom';
import { merchantApi, useApi } from '../../api';
import { CardMark, StatusBadge, TypeBadge } from '../../components/Badges';
import { Status } from '../../components/Status';
import { dateTime, money } from '../../format';
import type { Transaction } from '../../types';
import { callerOf, can, useMe } from '../session/session';
import { PaymentTxsSection } from '../txs/PaymentTxsSection';

/**
 * One payment: who paid with which card, the PSP reference, the timeline, and for a marketplace payment all its
 * splits (each seller's part and the commission). Route /merchants/:merchant/transactions/:paymentId.
 */
export function TransactionDetailPage() {
  const { merchant = 'me', paymentId = '' } = useParams();
  const me = useMe();
  // txs and journal entries: finance and admin only (merchants never see them)
  const showsTxs = callerOf(me) === 'staff' && can(me, 'ledger:read');
  const { data, error, loading } = useApi<Transaction>(merchantApi.transaction(merchant, paymentId));
  const back = `/merchants/${encodeURIComponent(merchant)}/transactions`;
  return (
    <>
      <p>
        <Link to={back}>← Transactions</Link>
      </p>
      <Status loading={loading} error={error} />
      {data && (
        <>
          <h1 className="title-row">
            {data.orderId} · {money(data.totalAmount.quantity, data.totalAmount.currency)}
            <TypeBadge processingModel={data.processingModel} />
            <StatusBadge status={data.status} />
          </h1>
          <div className="detail-grid">
            <section className="card">
              <dl>
                <dt>Payment</dt>
                <dd>{data.paymentId}</dd>
                <dt>Payment intent</dt>
                <dd>{data.paymentIntentId}</dd>
                <dt>Merchant</dt>
                <dd>{data.merchantAccount}</dd>
                <dt>Buyer</dt>
                <dd>{data.buyerId}</dd>
                <dt>PSP reference</dt>
                <dd>{data.pspReference}</dd>
                <dt>Payment method</dt>
                <dd>
                  <CardMark card={data.card} />
                </dd>
                <dt>Amount</dt>
                <dd>{money(data.totalAmount.quantity, data.totalAmount.currency)}</dd>
              </dl>
            </section>
            <section className="card timeline">
              <h2>Timeline</h2>
              <ol>
                <li className={data.authorizedAt ? 'done' : ''}>Authorized · {dateTime(data.authorizedAt)}</li>
                <li className={data.capturedAt ? 'done' : ''}>Captured · {dateTime(data.capturedAt)}</li>
                <li className={data.settledAt ? 'done' : ''}>Settled · {dateTime(data.settledAt)}</li>
              </ol>
            </section>
          </div>
          {data.processingModel === 'MARKETPLACE' ? (
            <section className="card">
              <h2>Splits</h2>
              <table>
                <thead>
                  <tr>
                    <th>Who</th>
                    <th>Part</th>
                    <th className="amount">Amount</th>
                  </tr>
                </thead>
                <tbody>
                  {(data.splits ?? []).map((split, index) => (
                    <tr key={index}>
                      <td>
                        {split.accountType === 'SELLER_PAYABLE' ? (
                          <Link to={`/merchants/${encodeURIComponent(merchant)}/sellers/${encodeURIComponent(split.account)}`}>
                            {split.account}
                          </Link>
                        ) : (
                          split.account
                        )}
                      </td>
                      <td>{split.accountType === 'SELLER_PAYABLE' ? "Seller's part" : 'Commission'}</td>
                      <td className="amount">{money(split.amount.quantity, split.amount.currency)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          ) : (
            <p className="muted">Direct sale: the whole amount is the merchant's (minus the platform fee).</p>
          )}
          {showsTxs && <PaymentTxsSection merchant={merchant} paymentId={paymentId} />}
        </>
      )}
    </>
  );
}
