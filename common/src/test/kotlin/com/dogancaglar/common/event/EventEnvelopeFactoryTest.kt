package com.dogancaglar.common.event

import com.dogancaglar.common.time.Utc
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class EventEnvelopeFactoryTest {

    data class TestEvent(
        override val eventType: String = "test_event",
        val x: Int=11,
        override val timestamp: Instant = Utc.nowInstant(),
        override val paymentIntentId: String ="121212",
        override val publicPaymentIntentId: String = "pi-121313",
        override val merchantAccountId: String= "Test"
    ) : Event {
        override fun deterministicEventId() = "fixed-id"
    }

    @Test
    fun `envelopeFor should generate deterministic envelope`() {
        val evt = TestEvent()

        val env = EventEnvelopeFactory.envelopeFor(
            data = evt,
            aggregateId = "agg-1",
            traceId = "trace-123"
        )

        assertEquals("fixed-id", env.eventId)
        assertEquals("test_event", env.eventType)
        assertEquals("agg-1", env.aggregateId)
        assertEquals("trace-123", env.traceId)
        assertEquals("fixed-id", env.parentEventId)
    }

    @Test
    fun `envelopeFor uses provided parentEventId`() {
        val evt = TestEvent(x=12)
        val env = EventEnvelopeFactory.envelopeFor(
            data = evt,
            aggregateId = "agg-1",
            traceId = "T",
            parentEventId = "parent-xyz"
        )

        assertEquals("parent-xyz", env.parentEventId)
    }

    @Test
    fun `envelopeFor uses explicit timestamp when passed`() {
        val t = Utc.toInstant(LocalDateTime.of(2020, 1, 1, 10, 0))
        val evt = TestEvent(timestamp = t)

        val env = EventEnvelopeFactory.envelopeFor(
            data = evt,
            aggregateId = "agg-1",
            traceId = "T",
            timestamp = t
        )

        assertEquals(t, env.timestamp)
        assertEquals(t, env.data.timestamp)
    }

    @Test
    fun `envelopeWithRandomId should generate random UUIDs`() {
        val evt = TestEvent(eventType = "hello")

        val e1 = EventEnvelopeFactory.envelopeWithRandomId(
            data = evt,
            aggregateId = "agg-1",
            traceId = "T"
        )

        val e2 = EventEnvelopeFactory.envelopeWithRandomId(
            data = evt,
            aggregateId = "agg-1",
            traceId = "T"
        )

        assertNotEquals(e1.eventId, e2.eventId)
        assertNotNull(e1.eventId)
        assertNotNull(e2.eventId)
    }

    @Test
    fun `envelopeWithRandomId uses random id as parentEventId fallback`() {
        val evt = TestEvent(eventType = "hello")

        val env = EventEnvelopeFactory.envelopeWithRandomId(
            data = evt,
            aggregateId = "a",
            traceId = "T"
        )

        assertEquals(env.eventId, env.parentEventId)
    }
}