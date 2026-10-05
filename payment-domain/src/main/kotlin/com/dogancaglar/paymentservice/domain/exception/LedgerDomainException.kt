package com.dogancaglar.paymentservice.domain.exception

/** A ledger rule was violated (our bug → non-retryable, DLQ, needs attention), or a tx is missing (API: 404). */
sealed class LedgerDomainException(message: String) : NonRetryableException(message) {

    /** A journal entry's debits and credits don't add up to the same amount. */
    class UnbalancedJournalEntryException(message: String) : LedgerDomainException(message)

    /** A journal entry needs at least two postings (one debit, one credit). */
    class LessThanTwoPostingsInJournalException(message: String) : LedgerDomainException(message)

    /** A journal entry posts to the same ledger account more than once. */
    class DuplicateAccountInJournalException(message: String) : LedgerDomainException(message)

    /** No tx with this id for this merchant (another merchant's is "not found" too). */
    class TxNotFoundException(message: String) : LedgerDomainException(message)

    /** Any other ledger rule (wrong account type, owner or code structure, invalid id). */
    class InvariantViolationException(message: String) : LedgerDomainException(message)
}
