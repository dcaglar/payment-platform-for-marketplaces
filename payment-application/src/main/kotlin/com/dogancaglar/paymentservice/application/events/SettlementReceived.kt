package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.application.dto.PaymentSplitDto
import com.dogancaglar.paymentservice.application.util.toPublicPaymentIntentId
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus
import java.time.Instant

data class SettlementReceived(
    override val paymentIntentId: String,
    override val publicPaymentIntentId: String,
    override val merchantAccountId: String,
    val grossAmountValue: Long,
    val netCashAmountValue: Long,
    val pspFeeAmountValue: Long,
    override val currency: String,
    override val timestamp: Instant = Utc.nowInstant(),
    ) : PaymentBaseEvent(paymentIntentId, publicPaymentIntentId, merchantAccountId ,grossAmountValue, currency, timestamp)  {
    override val eventType: String = EventType.SETTLEMENT_RECEIVED
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
