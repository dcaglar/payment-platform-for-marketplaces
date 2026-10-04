package com.dogancaglar.common.event

import java.time.Instant

/** Anything that travels through the outbox and Kafka inside an [EventEnvelope]. */
interface Event {
    val eventType: String
    fun deterministicEventId(): String
    val timestamp: Instant // when this event was produced
}
