import { money } from '../../format';
import type { Balance } from '../../types';

/** A seller's balance: only what we owe it. The ledger accounts behind it are not the seller's business. */
export function BalanceCard({ balance }: { balance: Balance }) {
  return (
    <div className="tiles">
      <div className="tile total">
        <span>Available balance ({balance.currency})</span>
        <strong>{money(balance.total, balance.currency)}</strong>
      </div>
    </div>
  );
}
