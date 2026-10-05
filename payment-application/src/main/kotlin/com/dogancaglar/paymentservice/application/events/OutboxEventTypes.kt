package com.dogancaglar.paymentservice.application.events

enum class OutboxEventTypes(val eventClass: Class<*>) {
    // 1. The Shopper completes the initial checkout successfully
    PAYMENT_AUTHORIZED(
        PaymentAuthorized::class.java
        // this represent an outboxevent which is persisted to edge-db outbox table when  payment-service call sync
        // external psp auth, and see itis authorized
    ),
    CAPTURE_CONFIRMED(
        CaptureConfirmed::class.java
        // this repsresent an outboxevent  which is persisted to edge-db outbox event   when  External Psp notifies us
        // about the final result of async capture  via a webhook  in payment-service
    ),
    INTERNAL_TRANSFER_COMMAND(InternalTransferCommand::class.java),
    SETTLEMENT_RECEIVED(SettlementReceived::class.java),

    // 2. The Merchant hits the Mor-DC API to initiate a capture
    CAPTURE_REQUESTED(
        CaptureRequested::class.java
        // this repsresent an outboxevent  which is persisted to eddge-db  when we receive a manual capture request from
        // merchant.
    ),

    // 3. The Mor-DC network worker gets a 202 ACK from Adyen/Stripe
    CAPTURE_SUBMITTED(
        CaptureSubmitted::class.java
        // this repsresent an outboxevent  which is persisted to central-db outbox event   when
        // CapturePspPerformedConsumer calls the external psp.capture async endpoint in payment-consuemrs
    ),

    JOURNAL_ENTRIES_RECORDED(JournalEntriesRecorded::class.java);

    companion object {
        fun from(value: String): OutboxEventTypes? =
            entries.find { it.name.equals(value, ignoreCase = true) }
    }
}
