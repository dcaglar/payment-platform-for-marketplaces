package com.dogancaglar.common.kafka.metadata

object Topics {
    // SHARED STREAMS (Multiple Event Types)
    // ---------------------------------------------------------
    // Holds both PAYMENT_AUTHORIZED and CAPTURE_CONFIRMED AND INTERNAL_TRANSFER_COMMAND and  SETTLEMENT_RECEIVED
    const val PSP_RESULTS = "payment.psp.results"

    // ---------------------------------------------------------
    // ISOLATED STREAMS (Specific Commands & ACKs)
    // ---------------------------------------------------------

    // CAPTURE_COMMAND is sent here
    const val CAPTURE_REQUESTED = "gateway.capture.requested"

    // CAPTURE_SUBMITTED is sent here
    const val CAPTURE_SUBMITTED_ACKS = "gateway.capture.submitted"

    const val JOURNAL_ENTRIES_RECORDED = "journal.entries.recorded"

    // ACCOUNT_CREATION_REQUESTED (POST /api/v1/accounts) is sent here
    const val ACCOUNT_CREATION_REQUESTED = "account.creation.requested"

    fun dlqOf(topic: String) = "$topic.DLQ"

    val ALL = listOf(
        PSP_RESULTS,
        JOURNAL_ENTRIES_RECORDED,
        CAPTURE_REQUESTED,
        CAPTURE_SUBMITTED_ACKS,
        ACCOUNT_CREATION_REQUESTED
    )
}

object CONSUMER_GROUPS {
    const val PSP_RESULT_CONSUMER = "payment.psp.result.consumer"
    const val CAPTURE_COMMAND_EXECUTOR = "capture.psp.command.executor"
    const val CAPTURE_SUBMITTED_CONSUMER = "capture.psp.submitted.ack.consumer"
    const val WEBHOOK_CAPTURE_CONFIRMED_PROCESSOR = "webhook.capture.confirmed.processor"
    const val ACCOUNT_BALANCE_CONSUMER = "accaount.balance.consumer"
    const val SETTLEMENT_RECORD_SIMULATOR = "settlement.record.simulator."
    const val ACCOUNT_CREATION_COMMAND_EXECUTOR = "account.creation.command.executor"
    const val TRANSACTION_CONSUMER = "transaction.consumer"

    /*
    const val PSP_RESULT_CONSUMER = "payment-core.psp-result-consumer"
    const val CAPT================================        URE_PSP_PERFORMED_CONSUMER = "payment-core.capture-psp-performed-consumer"

    // Gateway Worker consumers
    const val CAPTURE_COMMAND_EXECUTOR = "gateway-workers.capture-command-executor"

    // Ledger consumers
    const val INTERNAL_TRANSFER_CONSUMER = "ledger-engine.internal-transfer-consumer"
    const val ACCOUNT_BALANCE_CONSUMER = "ledger-engine.account-balance-consumer"
     */
}
