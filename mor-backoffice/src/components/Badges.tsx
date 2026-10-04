import type { Card, Transaction } from '../types';

/** Direct sale or marketplace (the payment's processing model). */
export function TypeBadge({ processingModel }: { processingModel: Transaction['processingModel'] }) {
  if (processingModel === 'MARKETPLACE') {
    return <span className="badge marketplace">Marketplace</span>;
  }
  return <span className="badge direct">Direct sale</span>;
}

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge ${status.toLowerCase()}`}>{status}</span>;
}

/** The card brand's logo and the last 4 digits; nothing else of a card is kept. */
export function CardMark({ card }: { card: Card | null }) {
  if (card === null) {
    return <span className="muted">—</span>;
  }
  return (
    <span className="card-mark">
      <BrandLogo brand={card.brand} />
      <span>•••• {card.last4}</span>
    </span>
  );
}

function BrandLogo({ brand }: { brand: Card['brand'] }) {
  if (brand === 'VISA') {
    return (
      <svg className="brand" viewBox="0 0 48 30" role="img" aria-label="Visa">
        <rect width="48" height="30" rx="4" fill="#1a1f71" />
        <text x="24" y="20" textAnchor="middle" fontSize="12" fontWeight="700" fontStyle="italic" fill="#ffffff" fontFamily="Arial, sans-serif">
          VISA
        </text>
      </svg>
    );
  }
  if (brand === 'MASTERCARD') {
    return (
      <svg className="brand" viewBox="0 0 48 30" role="img" aria-label="Mastercard">
        <rect width="48" height="30" rx="4" fill="#ffffff" stroke="#dee2e6" />
        <circle cx="19" cy="15" r="9" fill="#eb001b" />
        <circle cx="29" cy="15" r="9" fill="#f79e1b" fillOpacity="0.9" />
      </svg>
    );
  }
  if (brand === 'AMEX') {
    return (
      <svg className="brand" viewBox="0 0 48 30" role="img" aria-label="American Express">
        <rect width="48" height="30" rx="4" fill="#2e77bc" />
        <text x="24" y="19" textAnchor="middle" fontSize="10" fontWeight="700" fill="#ffffff" fontFamily="Arial, sans-serif">
          AMEX
        </text>
      </svg>
    );
  }
  return (
    <svg className="brand" viewBox="0 0 48 30" role="img" aria-label="Card">
      <rect width="48" height="30" rx="4" fill="#868e96" />
      <rect x="6" y="8" width="10" height="8" rx="1" fill="#f1f3f5" />
    </svg>
  );
}
