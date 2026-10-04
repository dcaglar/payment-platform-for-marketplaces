import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { can, useMe } from '../session/session';

/**
 * Staff's start page: staff look at one merchant at a time, named by its code (as in the API's
 * /<api>/merchants/{merchantAccount}… URLs). There is no merchant list endpoint yet, so the code is typed in.
 */
export function MerchantPickerPage() {
  const me = useMe();
  const navigate = useNavigate();
  const [merchant, setMerchant] = useState('');

  function open(where: 'transactions' | 'balances') {
    return (event?: FormEvent) => {
      event?.preventDefault();
      const code = merchant.trim();
      if (code !== '') {
        navigate(`/merchants/${encodeURIComponent(code)}/${where}`);
      }
    };
  }

  return (
    <>
      <h1>Merchants</h1>
      <form className="filters" onSubmit={open('transactions')}>
        <input placeholder="Merchant code, e.g. MARKETPLACE-5" value={merchant} onChange={(e) => setMerchant(e.target.value)} />
        {can(me, 'transaction:read') && (
          <button type="submit">Transactions</button>
        )}
        {can(me, 'balance:read') && (
          <button type="button" onClick={() => open('balances')()}>
            Balances
          </button>
        )}
      </form>
    </>
  );
}
