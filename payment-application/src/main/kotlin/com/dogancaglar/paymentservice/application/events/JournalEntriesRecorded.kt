package com.dogancaglar.paymentservice.application.events

import java.time.Instant

data class JournalEntriesRecorded(
    override val paymentIntentId: String,
    override val publicPaymentIntentId: String,
    override val merchantAccount: String,
    val customPartitionKey: String?,
    override val amountValue: Long,
    override val currency: String,
    val ledgerBatchId: String,
    val ledgerEntries: List<JournalEntryEventData>,
    // only on the AUTHORIZATION booking: who and what the payment is (null on every other booking)
    val authorization: AuthorizationDetails? = null,
    override val timestamp: Instant
) : PaymentBaseEvent(paymentIntentId, publicPaymentIntentId, merchantAccount, amountValue, currency, timestamp) {

    override val eventType: String = EVENT_TYPE

    override fun deterministicEventId() = ledgerBatchId

    companion object {
        const val EVENT_TYPE = EventType.JOURNAL_ENTRIES_RECORDED

        /**
         * Factory for use by RecordLedgerEntriesService.
         */
        fun from(
            cmd: PaymentBaseEvent,
            batchId: String,
            entries: List<JournalEntryEventData>,
            customPartitionKey: String,
            now: Instant,
            authorization: AuthorizationDetails? = null
        ) = JournalEntriesRecorded(
            paymentIntentId = cmd.paymentIntentId,
            publicPaymentIntentId = cmd.publicPaymentIntentId,
            merchantAccount = cmd.merchantAccount,
            customPartitionKey = customPartitionKey,
            amountValue = cmd.amountValue,
            currency = cmd.currency,
            ledgerBatchId = batchId,
            ledgerEntries = entries,
            authorization = authorization,
            timestamp = now
        )
    }
}
