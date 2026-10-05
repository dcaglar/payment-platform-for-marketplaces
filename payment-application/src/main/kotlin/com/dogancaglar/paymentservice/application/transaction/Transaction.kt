package com.dogancaglar.paymentservice.application.transaction

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.payment.CardSummary
import com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit
import com.dogancaglar.paymentservice.domain.model.payment.PaymentStatus
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.OrderId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import java.time.Instant

/**
 * An authorized payment as the back office displays it: one row per Payment, denormalized from the ledger's
 * events. Not to be confused with Tx (one PSP interaction of a Payment). Capture and settlement times are
 * added as they happen.
 */
data class Transaction(
    val paymentId: PaymentId,
    val paymentIntentId: PaymentIntentId,
    val publicPaymentIntentId: String,
    val merchantAccount: String,
    val buyerId: BuyerId,
    val orderId: OrderId,
    val pspReference: String,
    val processingModel: ProcessingModel,
    val totalAmount: Amount,
    val splits: List<PaymentSplit>,
    val authorizedAt: Instant,
    val capturedAt: Instant? = null,
    val settledAt: Instant? = null,
    val cardSummary: CardSummary? = null // brand + last 4 (null if the PSP did not say)
) {
    /** What the back office shows as the status: the furthest step reached. */
    fun status(): PaymentStatus {
        return when {
            settledAt != null -> PaymentStatus.SETTLED
            capturedAt != null -> PaymentStatus.CAPTURED
            else -> PaymentStatus.AUTHORIZED
        }
    }
}
