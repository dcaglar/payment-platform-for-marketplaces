import { money } from '../../format';
import type { Balance } from '../../types';

/** One owner's balance: each payable account and the total we owe it. */
export function BalanceCard({ balance }: { balance: Balance }) {
  return (
    <section className="card">
      <h2>
        {balance.ownerId} <span className="muted">({balance.ownerType.toLowerCase()})</span>
      </h2>
      <table>
        <tbody>
          {balance.accounts.map((account) => (
            <tr key={account.accountCode}>
              <td>{account.accountType}</td>
              <td className="muted">{account.accountCode}</td>
              <td className="amount">{money(account.balance, balance.currency)}</td>
            </tr>
          ))}
          <tr className="total">
            <td colSpan={2}>Total</td>
            <td className="amount">{money(balance.total, balance.currency)}</td>
          </tr>
        </tbody>
      </table>
    </section>
  );
}
