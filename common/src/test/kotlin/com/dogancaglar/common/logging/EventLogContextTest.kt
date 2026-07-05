package com.dogancaglar.common.logging

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.EventEnvelopeFactory
import com.dogancaglar.common.logging.EventLogContext
import com.dogancaglar.common.time.Utc
import junit.framework.TestCase.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.time.Instant
import kotlin.test.assertEquals

class EventLogContextTest {

    data class TestEvent(
        override val eventType: String = "x",
        override val timestamp: Instant = Utc.nowInstant(),
        override val paymentIntentId: String,
        override val publicPaymentIntentId: String,
        override val merchantAccountId: String
    ) : Event {
        override fun deterministicEventId() = "id-x"
    }

    @Test
    fun `with(EventEnvelope) populates and restores MDC`() {
        val env = EventEnvelopeFactory.envelopeFor(
            TestEvent(paymentIntentId = "1212", publicPaymentIntentId = "pi-3234234", merchantAccountId = "Test"),
            aggregateId = "agg-1",
            traceId = "trace-1"
        )

        assertTrue(MDC.getCopyOfContextMap()?.isEmpty() ?: true)

        EventLogContext.with(env) {
            assertEquals("trace-1", MDC.get("traceId"))
            assertEquals("agg-1", MDC.get("aggregateId"))
        }

        assertTrue(MDC.getCopyOfContextMap()?.isEmpty() ?: true)
    }

    @Test
    fun `nested with calls override and then restore MDC`() {
        val e1 = EventEnvelopeFactory.envelopeFor(TestEvent(paymentIntentId = "1212", publicPaymentIntentId = "pi-3234234", merchantAccountId = "Test"), "A", "T1")
        val e2 = EventEnvelopeFactory.envelopeFor(TestEvent(paymentIntentId = "1212", publicPaymentIntentId = "pi-3234234", merchantAccountId = "Test"), "B", "T2")

        EventLogContext.with(e1) {
            assertEquals("T1", MDC.get("traceId"))

            EventLogContext.with(e2) {
                assertEquals("T2", MDC.get("traceId"))
            }

            // restored
            assertEquals("T1", MDC.get("traceId"))
        }
    }

    @Test
    fun `withRetryFields sets retry fields and restores`() {
        val env = EventEnvelopeFactory.envelopeFor(TestEvent(paymentIntentId = "1212", publicPaymentIntentId = "pi-3234234", merchantAccountId = "Test"),"A", "T")
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
            assertEquals("T", MDC.get("traceId"))
        }
    }
}