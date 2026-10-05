package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.common.time.Utc
import java.time.Instant

data class CaptureConfirmed(
    override val paymentIntentId: String,
    override val publicPaymentIntentId: String,
    override val merchantAccount: String,
    override val amountValue: Long,
    override val currency: String,
    override val timestamp: Instant = Utc.nowInstant()
) : PaymentBaseEvent(paymentIntentId, publicPaymentIntentId, merchantAccount, amountValue, currency, timestamp) {

    override val eventType: String = EventType.CAPTURE_CONFIRMED

    companion object {
        @Suppress("LongParameterList") // one argument per event field
        fun from(
            paymentIntentId: String,
            publicPaymentIntentId: String,
            merchantAccount: String,
            amountValue: Long,
            currency: String,
            timestamp: Instant = Utc.nowInstant()
        ) = CaptureConfirmed(
            paymentIntentId = paymentIntentId,
            publicPaymentIntentId = publicPaymentIntentId,
            merchantAccount = merchantAccount,
            amountValue = amountValue,
            currency = currency,
            timestamp = timestamp
        )
    }
}
