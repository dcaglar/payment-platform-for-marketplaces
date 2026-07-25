package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.common.time.Utc
import java.time.Instant

data class CaptureConfirmed(
    override val paymentIntentId: String,
    override val publicPaymentIntentId: String,
    override val merchantAccountId: String,
    override val amountValue: Long,
    override val currency: String,
    override val timestamp: Instant = Utc.nowInstant()
) : PaymentBaseEvent(paymentIntentId, publicPaymentIntentId, merchantAccountId,amountValue, currency, timestamp) {

    override val eventType: String = EventType.CAPTURE_CONFIRMED

    companion object {
        fun from(
            paymentIntentId: String,
            publicPaymentIntentId: String,
            merchantAccountId: String,
            amountValue: Long,
            currency: String,
            timestamp: Instant = Utc.nowInstant()
        ) = CaptureConfirmed(
            paymentIntentId = paymentIntentId,
            publicPaymentIntentId = publicPaymentIntentId,
            merchantAccountId = merchantAccountId,
            amountValue = amountValue,
            currency = currency,
            timestamp = timestamp
        )
    }
}