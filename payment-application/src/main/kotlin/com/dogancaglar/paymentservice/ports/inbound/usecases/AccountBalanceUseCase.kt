package com.dogancaglar.paymentservice.ports.inbound.usecases

import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry

/**
 * AccountBalanceUseCase
 *
 * Mandate: Maintains account balance aggregations (snapshot + delta) optimized
 * for high-throughput reads and eventual consistency.
 */
interface AccountBalanceUseCase {

    /**
     * Updates account balances in-memory or in Redis (cache layer) based on the
     * provided journal entries.
     *
     * @param entries Domain-level JournalEntry list containing the postings to apply.
     * @return List of successfully updated Account IDs (for cache invalidation/monitoring).
     */
    fun updateAccountBalancesBatch(entries: List<JournalEntry>): List<Long>
}
