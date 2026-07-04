package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.time.Utc
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
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
@JsonIgnoreProperties(ignoreUnknown = true)
abstract class PaymentBaseEvent(
    @JsonProperty("paymentIntentId") override val paymentIntentId: String,
    @JsonProperty("publicPaymentIntentId") override val publicPaymentIntentId: String,
    @JsonProperty("merchantAccountId") override val merchantAccountId: String,
    @JsonProperty("amountValue") open val amountValue: Long,
    @JsonProperty("currency")  open val currency: String,
    @JsonProperty("timestamp") override val timestamp: Instant = Utc.nowInstant()
) : Event {

    abstract override val eventType: String

    // Standardized logic for everyone
    override fun deterministicEventId(): String = "$publicPaymentIntentId:$eventType"
}