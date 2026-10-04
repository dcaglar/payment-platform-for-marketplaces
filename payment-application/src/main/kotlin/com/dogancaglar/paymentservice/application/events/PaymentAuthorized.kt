package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.application.dto.PaymentSplitDto
import com.dogancaglar.paymentservice.application.util.toPublicPaymentIntentId
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus
import java.time.Instant

data class PaymentAuthorized(
    override val paymentIntentId: String,
    override val publicPaymentIntentId: String,
    override val merchantAccount: String,
    val buyerId: String,
    val orderId: String,
    val pspReference: String,
    val processingModel: String,
    val totalAmountValue: Long,
    override val currency: String,
    val splits: List<PaymentSplitDto>,
    val cardBrand: String? = null, // the card the PSP authorized: brand + last 4 only (null if the PSP did not say)
    val cardLast4: String? = null,
    override val timestamp: Instant = Utc.nowInstant(),
    val isSale: Boolean? = true
) : PaymentBaseEvent(paymentIntentId, publicPaymentIntentId, merchantAccount, totalAmountValue, currency, timestamp) {

    override val eventType: String = EventType.PAYMENT_AUTHORIZED
    override val amountValue: Long get() = totalAmountValue

    companion object {
        fun from(paymentIntent: PaymentIntent, timestamp: Instant = Utc.nowInstant()): PaymentAuthorized {
            require(paymentIntent.status == PaymentIntentStatus.AUTHORIZED) {
                "PaymentAuthorized requires AUTHORIZED status, but was ${paymentIntent.status}"
            }
            return PaymentAuthorized(
                paymentIntentId = paymentIntent.paymentIntentId.value.toString(),
                publicPaymentIntentId = paymentIntent.paymentIntentId.toPublicPaymentIntentId(),
                buyerId = paymentIntent.buyerId.value,
                orderId = paymentIntent.orderId.value,
                pspReference = paymentIntent.pspReferenceOrThrow(),
                merchantAccount = paymentIntent.merchantAccount,
                processingModel = paymentIntent.processingModel.name,
                totalAmountValue = paymentIntent.totalAmount.quantity,
                currency = paymentIntent.totalAmount.currency.currencyCode,
                splits = paymentIntent.splits.map { PaymentSplitDto.fromDomain(it) },
                cardBrand = paymentIntent.cardSummary?.brand?.name,
                cardLast4 = paymentIntent.cardSummary?.last4,
                timestamp = timestamp
            )
        }
    }
}
