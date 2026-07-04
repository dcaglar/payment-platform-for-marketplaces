package com.dogancaglar.common.event

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.time.LocalDateTime


interface Event {
    val eventType: String
    val paymentIntentId: String
    val publicPaymentIntentId: String
    val merchantAccountId: String
    fun deterministicEventId(): String
    val timestamp: Instant   // when this event was produced
}


