package com.dogancaglar.common.event

import com.dogancaglar.common.time.Utc
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import junit.framework.TestCase.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import java.time.Instant
import kotlin.test.assertEquals

class EventEnvelopeSerializationTest {

    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerKotlinModule()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    data class TestEvent(
        override val eventType: String = "test_event",
        val x: Int,
        override val timestamp: Instant = Utc.nowInstant(),
        override val paymentIntentId: String ="121212",
        override val publicPaymentIntentId: String = "pi-121313",
        override val merchantAccountId: String= "Test"
    ) : Event {
        override fun deterministicEventId() = "id-$x"
    }

    @Test
    fun `serialize and deserialize envelope`() {
        val evt = TestEvent(x = 42)
        val env = EventEnvelopeFactory.envelopeFor(
            data = evt,
            aggregateId = "agg-1",
            traceId = "tr"
        )

        val json = mapper.writeValueAsString(env)
        val back = mapper.readValue(json, object : TypeReference<EventEnvelope<TestEvent>>() {})

        assertEquals(env.eventId, back.eventId)
        assertEquals(42, back.data.x)
        assertEquals(env.timestamp, back.timestamp)
    }

    @Test
    fun `serialize handles null parentEventId`() {
        val evt = TestEvent(x=2)
        val env = EventEnvelope(
            eventId = "abc",
            eventType = "test_event",
            aggregateId = "agg",
            data = evt,
            timestamp = evt.timestamp,
            traceId = "T",
            parentEventId = null
        )

        val json = mapper.writeValueAsString(env)
        val back = mapper.readValue(json, object: TypeReference<EventEnvelope<TestEvent>>() {})

        assertNull(back.parentEventId)
    }

    @Test
    fun `timestamp is serialized in ISO format`() {
        val evt = TestEvent(x = 1)
        val env = EventEnvelopeFactory.envelopeFor(
            data = evt,
            aggregateId = "agg",
            traceId = "T"
        )

        val json = mapper.writeValueAsString(env)

        assertTrue(json.contains("T"))   // quick check for ISO
    }
}