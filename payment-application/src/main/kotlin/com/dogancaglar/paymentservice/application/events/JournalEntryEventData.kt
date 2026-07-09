package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import java.time.Instant

/**
 * Serializable DTO representing a ledger entry for event publication.
 * Contains the essential information needed by consumers (e.g., AccountBalanceConsumer)
 * to process balance updates without querying the database.
 * 
 * This class is only created through the factory method to ensure invariants are maintained.
 */
data class JournalEntryEventData private constructor(
    val journalEntryId: String, // e.g., "AUTH:paymentorder-123"
    val globalJournalEntryId: Long,
    val journalType: JournalType,
    val journalName: String?,
    val paymentId: Long,
    val txId: Long?,
    val reason : String?,
    val createdAt: Instant,
    val postings: List<PostingEventData>
) {
    companion object {
        fun create(
            journalEntryId: String,
            globalJournalEntryId: Long,
            journalType: JournalType,
            journalName: String?,
            paymentId: Long,
            txId: Long?,
            reason: String?,
            createdAt: Instant,
            postings: List<PostingEventData>
        ): JournalEntryEventData {
            return JournalEntryEventData(
                journalEntryId = journalEntryId,
                globalJournalEntryId = globalJournalEntryId,
                journalType = journalType,
                journalName = journalName,
                paymentId = paymentId,
                txId = txId,
                reason = reason,
                createdAt = createdAt,
                postings = postings
            )
        }
    }
}


