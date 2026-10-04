import { useState, type FormEvent } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { merchantApi, useApi } from '../../api';
import { CardMark, StatusBadge, TypeBadge } from '../../components/Badges';
import { Pager } from '../../components/Pager';
import { Status } from '../../components/Status';
import { dateTime, money } from '../../format';
import type { Page, Transaction } from '../../types';

const PAGE_SIZE = 20;
// what a payment can show (Transaction.status() in payment-application)
const STATUSES = ['AUTHORIZED', 'CAPTURED', 'SETTLED'];

/**
 * Menu "Transactions": the merchant's payments, newest first. Filters: order no., type, status, date range (whole
 * days, UTC). The filters and the page live in the URL, so a filtered list can be shared or reloaded.
 * Route /merchants/:merchant/transactions (merchant = "me" or a code).
 */
export function TransactionListPage() {
  const { merchant = 'me' } = useParams();
  const [search, setSearch] = useSearchParams();
  const [orderId, setOrderId] = useState(search.get('orderId') ?? '');
  const [type, setType] = useState(search.get('type') ?? '');
  const [status, setStatus] = useState(search.get('status') ?? '');
  const [fromDate, setFromDate] = useState(search.get('fromDate') ?? '');
  const [toDate, setToDate] = useState(search.get('toDate') ?? '');

  const { data, error, loading } = useApi<Page<Transaction>>(merchantApi.transactions(merchant, apiQuery(search)));

  function applyFilters(event: FormEvent) {
    event.preventDefault();
    const next = new URLSearchParams();
    if (orderId.trim() !== '') {
      next.set('orderId', orderId.trim());
    }
    if (type !== '') {
      next.set('type', type);
    }
    if (status !== '') {
      next.set('status', status);
    }
    if (fromDate !== '') {
      next.set('fromDate', fromDate);
    }
    if (toDate !== '') {
      next.set('toDate', toDate);
    }
    setSearch(next);
  }

  function goToPage(page: number) {
    const next = new URLSearchParams(search);
    next.set('page', String(page));
    setSearch(next);
  }

  return (
    <>
      <h1>{merchant === 'me' ? 'Transactions' : `Transactions of ${merchant}`}</h1>
      <form className="filters" onSubmit={applyFilters}>
        <input placeholder="Order no." value={orderId} onChange={(e) => setOrderId(e.target.value)} />
        <select value={type} onChange={(e) => setType(e.target.value)}>
          <option value="">Type: all</option>
          <option value="DIRECT_MERCHANT">Direct sale</option>
          <option value="MARKETPLACE">Marketplace</option>
        </select>
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">Status: all</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
        <label className="muted">
          From <input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} />
        </label>
        <label className="muted">
          To <input type="date" value={toDate} onChange={(e) => setToDate(e.target.value)} />
        </label>
        <button type="submit">Filter</button>
      </form>
      <Status loading={loading} error={error} />
      {data && (
        <>
          <table className="list">
            <thead>
              <tr>
                <th>Date</th>
                <th>Order</th>
                <th>Payment</th>
                <th>Type</th>
                <th>Payment method</th>
                <th className="amount">Amount</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((transaction) => (
                <tr key={transaction.paymentId}>
                  <td>{dateTime(transaction.authorizedAt)}</td>
                  <td>{transaction.orderId}</td>
                  <td>
                    <Link to={`/merchants/${encodeURIComponent(merchant)}/transactions/${transaction.paymentId}`}>
                      {transaction.paymentIntentId}
                    </Link>
                  </td>
                  <td>
                    <TypeBadge processingModel={transaction.processingModel} />
                  </td>
                  <td>
                    <CardMark card={transaction.card} />
                  </td>
                  <td className="amount">{money(transaction.totalAmount.quantity, transaction.totalAmount.currency)}</td>
                  <td>
                    <StatusBadge status={transaction.status} />
                  </td>
                </tr>
              ))}
              {data.items.length === 0 && (
                <tr>
                  <td colSpan={7} className="muted">
                    No transactions.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          <Pager page={data} onPage={goToPage} />
        </>
      )}
    </>
  );
}

/** The page's filters (in the URL) as the API's query: dates become whole UTC days, "to" inclusive. */
function apiQuery(search: URLSearchParams): URLSearchParams {
  const query = new URLSearchParams();
  query.set('page', search.get('page') ?? '0');
  query.set('size', String(PAGE_SIZE));
  const orderId = search.get('orderId');
  if (orderId) {
    query.set('orderId', orderId);
  }
  const type = search.get('type');
  if (type) {
    query.set('processingModel', type);
  }
  const status = search.get('status');
  if (status) {
    query.set('status', status);
  }
  const fromDate = search.get('fromDate');
  if (fromDate) {
    query.set('from', `${fromDate}T00:00:00Z`);
  }
  const toDate = search.get('toDate');
  if (toDate) {
    const dayAfter = new Date(`${toDate}T00:00:00Z`);
    dayAfter.setUTCDate(dayAfter.getUTCDate() + 1);
    query.set('to', dayAfter.toISOString());
  }
  return query;
}
