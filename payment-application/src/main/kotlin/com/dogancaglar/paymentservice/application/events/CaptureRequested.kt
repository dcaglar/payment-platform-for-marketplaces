package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.application.util.toPublicPaymentIntentId
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
data class CaptureRequested(
    override val paymentIntentId: String,
    override val publicPaymentIntentId: String,
    override val merchantAccountId: String,
    override val amountValue: Long,
    override val currency: String,
    val attempt: Int = 1,
    override val timestamp: Instant = Utc.nowInstant()
) : PaymentBaseEvent(paymentIntentId, publicPaymentIntentId, merchantAccountId,amountValue, currency, timestamp) {

    override val eventType: String = EventType.CAPTURE_REQUESTED

    // Override because attempt is a meaningful discriminator for retries
    override fun deterministicEventId(): String = "$publicPaymentIntentId:$eventType:$attempt"

    fun withIncrementedAttempt(): CaptureRequested = copy(attempt = attempt + 1, timestamp = Utc.nowInstant())

    companion object {
        fun from(paymentIntent: PaymentIntent, captureAmount: Amount, timestamp: Instant = Utc.nowInstant()) = CaptureRequested(
            paymentIntentId = paymentIntent.paymentIntentId.value.toString(),
            publicPaymentIntentId = paymentIntent.paymentIntentId.toPublicPaymentIntentId(),
            merchantAccountId = paymentIntent.merchantAccount,
            amountValue = captureAmount.quantity,
            currency = captureAmount.currency.currencyCode,
            attempt = 1,
            timestamp = timestamp
        )
    }
}