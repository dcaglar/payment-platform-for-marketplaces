package com.dogancaglar.paymentservice.infra.adapter.outbound.kafka

import com.dogancaglar.common.event.metadata.EventMetaDataRegistry
import com.dogancaglar.common.kafka.metadata.PaymentEventMetadataCatalog
import com.dogancaglar.common.kafka.publisher.RawEventPublisher
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

/**
 * Unit tests for RawEventPublisher — ordering layer L3 (partition routing) and the
 * relay's raw-bytes contract:
 * - record KEY = outboxEvent.partitionKey (same payment → same Kafka partition)
 * - TOPIC resolved from the metadata registry by eventType (never caller-supplied)
 * - parentEventId propagated as a header (present when set)
 * - payload passed through as the raw pre-serialized string — no deserialization
 */
class RawEventPublisherTest {

    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
    private val registry = EventMetaDataRegistry(PaymentEventMetadataCatalog.all)
    private val publisher = RawEventPublisher(kafkaTemplate, registry)
    private val recordSlot = slot<ProducerRecord<String, String>>()

    private fun stubSend() {
        val sendResult = mockk<SendResult<String, String>>(relaxed = true)
        every { kafkaTemplate.send(capture(recordSlot)) } returns
            CompletableFuture.completedFuture(sendResult)
    }

    private fun outboxEvent(parentEventId: String?) = OutboxEvent.createNew(
        oeid = 42,
        partitionKey = "pk-payment-7",
        eventType = "payment_authorized",
        aggregateId = "payment-7",
        eventId = "evt-42",
        parentEventId = parentEventId,
        payload = """{"pre":"serialized","raw":"payload"}"""
    )

    @Test
    fun `record key must be the partitionKey and topic must come from the metadata registry`() {
        stubSend()

        publisher.publishRaw(outboxEvent(parentEventId = "evt-41")).join()

        val record = recordSlot.captured
        assertEquals("pk-payment-7", record.key())
        assertEquals(
            registry.metadataFor<com.dogancaglar.common.event.Event>("payment_authorized").topic,
            record.topic()
        )
        assertTrue(record.topic().isNotBlank())
    }

    @Test
    fun `payload must pass through untouched as raw string`() {
        stubSend()

        publisher.publishRaw(outboxEvent(parentEventId = "evt-41")).join()

        assertEquals("""{"pre":"serialized","raw":"payload"}""", recordSlot.captured.value())
    }

    @Test
    fun `parentEventId must be propagated as a kafka header when present`() {
        stubSend()

        publisher.publishRaw(outboxEvent(parentEventId = "evt-41")).join()

        val header = recordSlot.captured.headers().lastHeader("parentEventId")
        assertNotNull(header)
        assertEquals("evt-41", String(header.value()))
    }
}
