package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.time.Utc
import java.time.Instant

/**
 * Common base for all Payment lifecycle events.
 *
 * The five shared fields (paymentIntentId, publicPaymentIntentId, amountValue,
 * currency, timestamp) are declared here with their @JsonProperty bindings once.
 * Subclasses only declare the fields that are unique to them.
 *
 * Default deterministicEventId() = "$publicPaymentIntentId:$eventType".
 * Override only when additional discriminators are needed (e.g. attempt, targetAccountId).
 */
abstract class PaymentBaseEvent(
    override val paymentIntentId: String,
    override val publicPaymentIntentId: String,
    override val merchantAccountId: String,
    open val amountValue: Long,
    open val currency: String,
    override val timestamp: Instant = Utc.nowInstant()
) : Event {

    abstract override val eventType: String

    // Standardized logic for everyone
    override fun deterministicEventId(): String = "$publicPaymentIntentId:$eventType"
}