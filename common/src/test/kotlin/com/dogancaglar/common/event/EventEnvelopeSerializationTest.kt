package com.dogancaglar.common.event

import com.dogancaglar.common.time.Utc
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Instant
import kotlin.test.assertEquals

class EventEnvelopeSerializationTest {

    private val mapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()

    data class TestEvent(
        override val eventType: String = "test_event",
        val x: Int,
        override val timestamp: Instant = Utc.nowInstant()
    ) : Event {
        override fun deterministicEventId() = "id-$x"
    }

    @Test
    fun `serialize and deserialize envelope`() {
        val evt = TestEvent(x = 42)
        val env = EventEnvelopeFactory.envelopeFor(
            data = evt,
            aggregateId = "agg-1"
        )

        val json = mapper.writeValueAsString(env)
        val back = mapper.readValue(json, object : TypeReference<EventEnvelope<TestEvent>>() {})

        assertEquals(env.eventId, back.eventId)
        assertEquals(42, back.data.x)
        assertEquals(env.timestamp, back.timestamp)
    }

    @Test
    fun `serialize handles null parentEventId`() {
        val evt = TestEvent(x = 2)
        val env = EventEnvelope(
            eventId = "abc",
            eventType = "test_event",
            aggregateId = "agg",
            data = evt,
            timestamp = evt.timestamp,
            parentEventId = null
        )

        val json = mapper.writeValueAsString(env)
        val back = mapper.readValue(json, object : TypeReference<EventEnvelope<TestEvent>>() {})

        assertNull(back.parentEventId)
    }

    @Test
    fun `timestamp is serialized in ISO format`() {
        val evt = TestEvent(x = 1)
        val env = EventEnvelopeFactory.envelopeFor(
            data = evt,
            aggregateId = "agg"
        )

        val json = mapper.writeValueAsString(env)

        assertTrue(json.contains("T")) // quick check for ISO
    }
}
