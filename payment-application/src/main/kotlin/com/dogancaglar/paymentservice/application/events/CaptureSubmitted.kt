package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.common.time.Utc
import java.time.Instant

data class CaptureSubmitted(
    val pspReference: String,
    override val paymentIntentId: String,
    override val publicPaymentIntentId: String,
    override val merchantAccountId: String,
    override val amountValue: Long,
    override val currency: String,
    override val timestamp: Instant = Utc.nowInstant()
) : PaymentBaseEvent(paymentIntentId, publicPaymentIntentId, merchantAccountId,amountValue, currency, timestamp) {

    override val eventType: String = EventType.CAPTURE_SUBMITTED
    // deterministicEventId() inherited: "$publicPaymentIntentId:$eventType"

    companion object {
        fun from(request: CaptureRequested, pspReference: String, timestamp: Instant = Utc.nowInstant()) = CaptureSubmitted(
            pspReference = pspReference,
            paymentIntentId = request.paymentIntentId,
            publicPaymentIntentId = request.publicPaymentIntentId,
            merchantAccountId = request.merchantAccountId,
            amountValue = request.amountValue,
            currency = request.currency,
            timestamp = timestamp
        )
    }
}