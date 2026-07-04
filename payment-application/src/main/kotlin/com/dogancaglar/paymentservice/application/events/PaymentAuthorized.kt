package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.application.dto.PaymentSplitDto
import com.dogancaglar.paymentservice.application.util.toPublicPaymentIntentId
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentAuthorized(
    override val paymentIntentId: String,
    override val publicPaymentIntentId: String,
    override val merchantAccountId: String,
    val buyerId: String,
    val processingModel: String,
    @JsonProperty("totalAmountValue") val totalAmountValue: Long,
     override val currency: String,
    val splits: List<PaymentSplitDto>,
    override val timestamp: Instant = Utc.nowInstant(),
    val isSale: Boolean? = true
) : PaymentBaseEvent( paymentIntentId,publicPaymentIntentId,merchantAccountId,totalAmountValue, currency, timestamp) {

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
                merchantAccountId = paymentIntent.merchantAccount,
                processingModel = paymentIntent.processingModel.name,
                totalAmountValue = paymentIntent.totalAmount.quantity,
                currency = paymentIntent.totalAmount.currency.currencyCode,
                splits = paymentIntent.splits.map { PaymentSplitDto.fromDomain(it) },
                timestamp = timestamp
            )
        }
    }
}
