import { txApi, useApi } from '../../api';
import { Status } from '../../components/Status';
import { money } from '../../format';
import type { Payment } from '../../types';

/**
 * Finance only, on the payment detail: every journal entry of the payment, one row per posting, in recorded order;
 * the amount in the Debit or the Credit column. Each entry's debits equal its credits.
 */
export function PaymentTxsSection({ merchant, paymentId }: { merchant: string; paymentId: string }) {
  const { data, error, loading } = useApi<Payment>(txApi.payment(merchant, paymentId));
  return (
    <section className="card finance">
      <h2>Journal entries (finance)</h2>
      <Status loading={loading} error={error} />
      {data && (
        <table className="postings">
          <thead>
            <tr>
              <th>Journal entry</th>
              <th>Account</th>
              <th className="amount">Debit</th>
              <th className="amount">Credit</th>
            </tr>
          </thead>
          <tbody>
            {data.journalEntries.map((entry) =>
              entry.postings.map((posting, index) => (
                <tr key={`${entry.id}-${posting.accountCode}`} className={index === 0 ? 'entry-start' : ''}>
                  <td>{entry.journalType}</td>
                  <td>{posting.accountCode}</td>
                  <td className="amount">{posting.direction === 'DEBIT' ? money(posting.amount.quantity, posting.amount.currency) : ''}</td>
                  <td className="amount">{posting.direction === 'CREDIT' ? money(posting.amount.quantity, posting.amount.currency) : ''}</td>
                </tr>
              ))
            )}
            {data.journalEntries.length === 0 && (
              <tr>
                <td colSpan={4} className="muted">
                  No journal entries yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}
    </section>
  );
}
