// The payment API's answers, as payment-consumers sends them (adapter/inbound/rest/dto). Amounts are in cents.

export interface Amount {
  quantity: number;
  currency: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface AccountBalance {
  accountType: string;
  accountCode: string;
  balance: number;
}

/** A seller's or a merchant's balance: its payable accounts and their total (what we owe it). */
export interface Balance {
  ownerType: 'SELLER' | 'MERCHANT';
  ownerId: string;
  currency: string;
  total: number;
  accounts: AccountBalance[];
  detailUrl: string | null;
}

export interface TransactionSplit {
  accountType: string;
  account: string;
  amount: Amount;
}

/** The card a payment was made with: brand and last 4 digits only. */
export interface Card {
  brand: 'VISA' | 'MASTERCARD' | 'AMEX' | 'OTHER';
  last4: string;
}

/** One authorized payment. buyerId, pspReference and splits come only in the detail. */
export interface Transaction {
  paymentId: string;
  paymentIntentId: string;
  orderId: string;
  merchantAccount: string;
  totalAmount: Amount;
  status: string;
  authorizedAt: string;
  capturedAt: string | null;
  settledAt: string | null;
  detailUrl: string;
  buyerId: string | null;
  pspReference: string | null;
  processingModel: 'MARKETPLACE' | 'DIRECT_MERCHANT';
  card: Card | null;
  splits: TransactionSplit[] | null;
}

/** Who is logged in (from the back office's own server, /auth/me). */
export interface Me {
  username: string;
  merchantId: string | null;
  sellerId: string | null;
  permissions: string[];
}

// --- finance: txs and journal entries (/api/v1/txs/…), as payment-consumers' TxController sends them ---

export interface Posting {
  accountCode: string;
  accountType: string;
  direction: 'DEBIT' | 'CREDIT';
  amount: Amount;
}

export interface JournalEntry {
  id: string;
  journalType: string;
  name: string;
  txId: string | null;
  postings: Posting[];
  totalDebit: Amount;
  totalCredit: Amount;
}

/** One tx (AUTHORIZATION, CAPTURE, SETTLEMENT, …); journalEntries only on the tx page. */
export interface Tx {
  txId: string;
  txType: string;
  paymentId: string;
  status: string;
  amount: Amount;
  acquirerReference: string | null;
  parentTxId: string | null;
  settleStatus: string | null;
  createdAt: string;
  detailUrl: string;
  journalEntries: JournalEntry[] | null;
}

/** A payment's txs and all its journal entries, in recorded order. */
export interface Payment {
  paymentId: string;
  merchantAccount: string;
  txs: Tx[];
  journalEntries: JournalEntry[];
}
