package com.dogancaglar.common.event

import com.dogancaglar.common.time.Utc
import java.time.Instant
import java.util.UUID

object EventEnvelopeFactory {

    fun <T : Event> envelopeFor(
        data: T,
        aggregateId: String,
        parentEventId: String? = null,
        timestamp: Instant = Utc.nowInstant()
    ): EventEnvelope<T> {
        val id = data.deterministicEventId()
        val resolvedParent = parentEventId ?: id

        return EventEnvelope(
            eventId = id,
            eventType = data.eventType,
            aggregateId = aggregateId,
            data = data,
            timestamp = timestamp,
            parentEventId = resolvedParent
        )
    }

    /**
     * Fallback factory when you explicitly do NOT want deterministic IDs
     * (e.g. fire-and-forget events).
     */
    fun <T : Event> envelopeWithRandomId(
        data: T,
        aggregateId: String,
        parentEventId: String? = null,
        timestamp: Instant = Utc.nowInstant()
    ): EventEnvelope<T> {
        val id = UUID.randomUUID().toString()
        val resolvedParent = parentEventId ?: id

        return EventEnvelope(
            eventId = id,
            eventType = data.eventType,
            aggregateId = aggregateId,
            data = data,
            timestamp = timestamp,
            parentEventId = resolvedParent
        )
    }
}