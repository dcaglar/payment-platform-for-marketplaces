import { useState } from 'react';

// Stripe test cards (https://docs.stripe.com/testing) and what our authorize endpoint answers for each.
// Expiry: any future date · CVC: any 3 digits (4 for Amex) · postal code: any.
const TEST_CARDS = [
  { number: '4242 4242 4242 4242', label: 'Visa', stripe: 'approved (held, not captured)', ours: '200 AUTHORIZED', kind: 'ok' },
  { number: '5555 5555 5555 4444', label: 'Mastercard', stripe: 'approved (held, not captured)', ours: '200 AUTHORIZED', kind: 'ok' },
  { number: '3782 822463 10005', label: 'American Express', stripe: 'approved (held, not captured)', ours: '200 AUTHORIZED', kind: 'ok' },
  { number: '4000 0000 0000 0002', label: 'Generic decline', stripe: 'declined: generic_decline', ours: '200 DECLINED', kind: 'decline' },
  { number: '4000 0000 0000 9995', label: 'Insufficient funds', stripe: 'declined: insufficient_funds', ours: '200 DECLINED', kind: 'decline' },
  { number: '4000 0000 0000 9987', label: 'Lost card', stripe: 'declined: lost_card', ours: '200 DECLINED', kind: 'decline' },
  { number: '4000 0000 0000 9979', label: 'Stolen card', stripe: 'declined: stolen_card', ours: '200 DECLINED', kind: 'decline' },
  { number: '4000 0000 0000 0069', label: 'Expired card', stripe: 'declined: expired card', ours: '200 DECLINED', kind: 'decline' },
  { number: '4000 0000 0000 0127', label: 'Incorrect CVC', stripe: 'declined: incorrect CVC', ours: '200 DECLINED', kind: 'decline' },
  { number: '4000 0000 0000 0119', label: 'Processing error', stripe: 'declined: processing error', ours: '200 DECLINED', kind: 'decline' },
  { number: '4100 0000 0000 0019', label: 'Blocked by Radar', stripe: 'declined: highest fraud risk', ours: '200 DECLINED', kind: 'decline' },
  { number: '4000 0000 0000 3220', label: '3-D Secure required', stripe: 'requires_action', ours: '202 PENDING_AUTH (stays: no 3DS step)', kind: 'pending' },
  { number: '4000 0027 6000 3184', label: '3-D Secure always', stripe: 'requires_action', ours: '202 PENDING_AUTH (stays: no 3DS step)', kind: 'pending' },
];

export function TestCards({ simulated }) {
  const [copied, setCopied] = useState(null);

  const copy = async (number) => {
    try {
      await navigator.clipboard.writeText(number.replace(/ /g, ''));
      setCopied(number);
      setTimeout(() => setCopied(null), 1500);
    } catch (err) {
      console.warn('Could not copy the card number:', err);
    }
  };

  return (
    <details className="test-cards" open>
      <summary>🧪 Test cards</summary>

      {simulated ? (
        <p className="test-cards-mode test-cards-mode-sim">
          <strong>Simulated PSP</strong> (payment-service runs with <code>psp.gateway.type: SIMULATED</code>):
          the card number does not decide the result. The simulator does, using the weights in
          <code> psp.authorization.simulation</code> (<code>NORMAL</code> approves every payment). To see
          declines, lower <code>successful</code>; the remainder of 100 becomes declines.
        </p>
      ) : (
        <p className="test-cards-mode test-cards-mode-stripe">
          <strong>Stripe test mode</strong>: the result depends on the card below.
        </p>
      )}

      <p className="test-cards-hint">Expiry: any future date · CVC: any 3 digits (4 for Amex) · postal code: any</p>

      <table className="test-cards-table">
        <thead>
          <tr>
            <th>Card</th>
            <th>Number</th>
            <th>Stripe</th>
            <th>Our API</th>
          </tr>
        </thead>
        <tbody>
          {TEST_CARDS.map((card) => (
            <tr key={card.number} className={`test-card-${card.kind}`}>
              <td>{card.label}</td>
              <td>
                <button type="button" className="copy-card-btn" onClick={() => copy(card.number)} title="Copy">
                  <code>{card.number}</code> {copied === card.number ? '✅' : '📋'}
                </button>
              </td>
              <td>{card.stripe}</td>
              <td>{card.ours}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </details>
  );
}
