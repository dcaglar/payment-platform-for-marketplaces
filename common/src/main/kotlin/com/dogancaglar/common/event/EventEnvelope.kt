package com.dogancaglar.common.event

import com.dogancaglar.common.time.Utc
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

data class EventEnvelope<T : Event> @JsonCreator constructor(
    @JsonProperty("eventId")
    val eventId: String,

    @JsonProperty("eventType")
    val eventType: String,

    @JsonProperty("aggregateId")
    val aggregateId: String,

    @JsonProperty("data")
    val data: T,

    @JsonProperty("timestamp")
    val timestamp: Instant = Utc.nowInstant(),

    @JsonProperty("parentEventId")
    val parentEventId: String? = null
)