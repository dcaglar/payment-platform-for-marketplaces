package com.dogancaglar.common.logging

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.EventEnvelopeFactory
import com.dogancaglar.common.time.Utc
import junit.framework.TestCase.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.time.Instant
import kotlin.test.assertEquals

class EventLogContextTest {

    data class TestEvent(
        override val eventType: String = "x",
        override val timestamp: Instant = Utc.nowInstant()
    ) : Event {
        override fun deterministicEventId() = "id-x"
    }

    @Test
    fun `with(EventEnvelope) populates and restores MDC`() {
        val env = EventEnvelopeFactory.envelopeFor(
            TestEvent(),
            aggregateId = "agg-1"
        )

        assertTrue(MDC.getCopyOfContextMap()?.isEmpty() ?: true)

        EventLogContext.with(env) {
            assertEquals("agg-1", MDC.get("aggregateId"))
        }

        assertTrue(MDC.getCopyOfContextMap()?.isEmpty() ?: true)
    }

    @Test
    fun `nested with calls override and then restore MDC`() {
        val e1 = EventEnvelopeFactory.envelopeFor(TestEvent(), "A")
        val e2 = EventEnvelopeFactory.envelopeFor(TestEvent(), "B")

        EventLogContext.with(e1) {
            assertEquals("A", MDC.get("aggregateId"))

            EventLogContext.with(e2) {
                assertEquals("B", MDC.get("aggregateId"))
            }

            // restored
            assertEquals("A", MDC.get("aggregateId"))
        }
    }

    @Test
    fun `withRetryFields sets retry fields and restores`() {
        val env = EventEnvelopeFactory.envelopeFor(TestEvent(), "A")
        EventLogContext.with(env) {
            EventLogContext.withRetryFields(
                retryCount = 3,
                retryReason = "timeout",
                backOffInMillis = 500L,
            ) {
                assertEquals("3", MDC.get("retryCount"))
                assertEquals("timeout", MDC.get("retryReason"))
            }

            // restored to envelope values
            assertEquals("A", MDC.get("aggregateId"))
        }
    }
}
