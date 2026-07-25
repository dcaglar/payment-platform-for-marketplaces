package com.dogancaglar.paymentservice.application.events

import java.time.Instant

data class JournalEntriesRecorded(
    override val paymentIntentId: String,
    override val publicPaymentIntentId: String,
    override val merchantAccountId: String,
    val customPartitionKey: String?,
    override val amountValue: Long,
    override val currency: String,
    val ledgerBatchId: String,
    val ledgerEntries: List<JournalEntryEventData>,
    override val timestamp: Instant
) : PaymentBaseEvent(paymentIntentId, publicPaymentIntentId, merchantAccountId ,amountValue, currency, timestamp) {

    override val eventType: String = EVENT_TYPE

   override  fun  deterministicEventId() = ledgerBatchId

    companion object {
        const val EVENT_TYPE = EventType.JOURNAL_ENTRIES_RECORDED

        /**
         * Factory for use by RecordLedgerEntriesService.
         */
        fun from(
            cmd: PaymentBaseEvent,
            batchId: String,
            entries: List<JournalEntryEventData>,
            customPartitionKey:  String,
            now: Instant
        ) = JournalEntriesRecorded(
            paymentIntentId = cmd.paymentIntentId,
            publicPaymentIntentId = cmd.publicPaymentIntentId,
            merchantAccountId = cmd.merchantAccountId,
            customPartitionKey =  customPartitionKey,
            amountValue = cmd.amountValue,
            currency = cmd.currency,
            ledgerBatchId = batchId,
            ledgerEntries = entries,
            timestamp = now
        )
    }
}