package com.dogancaglar.common.event.metadata

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.logging.EventLogContextTest.TestEvent
import com.dogancaglar.common.time.Utc
import com.fasterxml.jackson.core.type.TypeReference
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventMetadataRegistryTest {

    data class TestEvent(
        override val eventType: String,
        override val timestamp: Instant = Utc.nowInstant(),
        override val paymentIntentId: String,
        override val publicPaymentIntentId: String,
        override val merchantAccountId: String
    ) : Event {
        override fun deterministicEventId() = "id-$eventType"
    }

    object TestMetadataA : EventMetadata<TestEvent> {
        override val topic = "topic-a"
        override val eventType = "a"
        override val clazz = TestEvent::class.java
        override val typeRef = object : TypeReference<EventEnvelope<TestEvent>>() {}
    }

    object TestMetadataB : EventMetadata<TestEvent> {
        override val topic = "topic-b"
        override val eventType = "b"
        override val clazz = TestEvent::class.java
        override val typeRef = object : TypeReference<EventEnvelope<TestEvent>>() {}
    }

    @Test
    fun `metadataFor returns correct metadata`() {
        val reg = EventMetaDataRegistry(listOf(TestMetadataA, TestMetadataB))
        assertEquals("topic-a", reg.metadataFor<TestEvent>("a").topic)
    }

    @Test
    fun `metadataForEvent resolves via event`() {
        val evt = TestEvent(eventType = "b",paymentIntentId = "1212", publicPaymentIntentId = "pi-3234234", merchantAccountId = "Test")
        val reg = EventMetaDataRegistry(listOf(TestMetadataA, TestMetadataB))
        assertEquals("topic-b", reg.metadataForEvent(evt).topic)
    }

    @Test
    fun `metadataFor throws if type missing`() {
        val reg = EventMetaDataRegistry(listOf(TestMetadataA))
        assertThrows<IllegalStateException> {
            reg.metadataFor<TestEvent>("missing")
        }
    }

    @Test
    fun `registry all returns all metadata`() {
        val reg = EventMetaDataRegistry(listOf(TestMetadataA, TestMetadataB))
        val all = reg.all().map { it.eventType }

        assertTrue(all.contains("a"))
        assertTrue(all.contains("b"))
    }
}