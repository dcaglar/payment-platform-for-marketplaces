package com.dogancaglar.paymentservice.ports.outbound

/**
 * Port for checking and marking ledger entry IDs as processed (idempotency).
 */
interface BalanceIdempotencyPort {
    /**
     * Checks if any of the ledger entry IDs have already been processed.
     * @return true if ANY of the IDs were already processed, false otherwise
     */
    fun areLedgerEntryIdsProcessed(ledgerEntryIds: List<Long>): Boolean

    /**
     * Marks ledger entry IDs as processed (idempotent: safe to call multiple times).
     * @param ledgerEntryIds List of IDs to mark as processed
     */
    fun markLedgerEntryIdsProcessed(ledgerEntryIds: List<Long>)
}
