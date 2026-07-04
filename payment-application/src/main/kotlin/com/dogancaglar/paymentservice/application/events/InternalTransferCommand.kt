package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.common.id.PublicIdFactory
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.payment.InternalTransfer
import java.time.Instant

data class InternalTransferCommand(
    val transferId: Long,
    val internalTransferTxId: Long,
    val sourceTransactionId: Long,
    override val amountValue: Long,
    override val currency: String,
    val targetAccount: String,
    val sourceAccount: String,
    val journalType: String,
    val status: String,
    override val paymentIntentId: String,
    override val publicPaymentIntentId: String,
    override val merchantAccountId: String,
    override val timestamp: Instant = Utc.nowInstant()
) : PaymentBaseEvent(paymentIntentId, publicPaymentIntentId, merchantAccountId,amountValue, currency, timestamp) {
    override val eventType: String = EventType.INTERNAL_TRANSFER_COMMAND

    companion object {
        fun from(transfer: InternalTransfer, txId: Long, paymentIntentId: String,journalType: String, // ◄ Passed from submission layer
                 publicPaymentIntentId: String): InternalTransferCommand {
            return InternalTransferCommand(
                transferId = transfer.transferId.value,
                internalTransferTxId = txId,
                sourceTransactionId = transfer.sourceTransactionId.value,
                paymentIntentId = transfer.paymentIntentId.toString(),
                publicPaymentIntentId = PublicIdFactory.publicPaymentIntentId(transfer.paymentIntentId.value),
                merchantAccountId = transfer.merchantAccountId,
                amountValue = transfer.amount.quantity,
                currency = transfer.amount.currency.currencyCode,
                journalType= journalType,
                sourceAccount = transfer.sourceAccount,
                targetAccount = transfer.targetAccount,
                status = transfer.status.name,
            )
        }
    }
}



