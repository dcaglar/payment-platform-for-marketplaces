package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.common.time.Utc
import java.time.Instant

data class SettlementReceived(
    override val paymentIntentId: String,
    override val publicPaymentIntentId: String,
    override val merchantAccount: String,
    val grossAmountValue: Long,
    val netCashAmountValue: Long,
    val pspFeeAmountValue: Long,
    override val currency: String,
    override val timestamp: Instant = Utc.nowInstant(),
) : PaymentBaseEvent(
    paymentIntentId,
    publicPaymentIntentId,
    merchantAccount,
    grossAmountValue,
    currency,
    timestamp
) {
    override val eventType: String = EventType.SETTLEMENT_RECEIVED
}
