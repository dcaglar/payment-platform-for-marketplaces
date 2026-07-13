/*
package com.dogancaglar.common.kafka.publisher

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.event.EventEnvelopeFactory
import com.dogancaglar.common.event.metadata.EventMetadata
import com.dogancaglar.common.event.metadata.EventMetaDataRegistry
import com.dogancaglar.paymentservice.ports.outbound.EventPublisherPort
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CompletableFuture
import org.apache.kafka.common.header.Headers

class PaymentEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, EventEnvelope<*>>,
    private val eventMetaDataRegistry: EventMetaDataRegistry
) : EventPublisherPort {

    private val logger = LoggerFactory.getLogger(javaClass)




    // ======================================================================
    // ASYNC PUBLISH
    // ======================================================================
    override fun <T : Event> publishAsync(
        envelope: EventEnvelope<T>
    ): CompletableFuture<EventEnvelope<T>> {
        val eventMetaData = eventMetaDataRegistry.metadataForEvent(envelope.data)
        val record = buildRecord(eventMetaData, envelope)

        logger.debug("🚀 PaymentEventPublisher: IS GOING TO SEND an RECORD with event type ${record.value().eventType} to Topic  ${record.topic()}")
        
        return kafkaTemplate.send(record)
            .thenApply { _ ->
                logger.debug("🚀 PaymentEventPublisher: JUSST SENT an RECORD with event type ${record.value().eventType} to Topic  ${record.topic()}")
                envelope
            }
            .exceptionally { ex ->
                logger.error(
                    "❌  PaymentEventPublisher: JUSST FAILED to SEND an RECORD with event type ${record.value().eventType} to Topic  ${record.topic()}",
                    ex
                )
                throw ex
            }
    }
    // ======================================================================
    // BUILD RECORD
    // ======================================================================
    private fun <T : Event> buildRecord(
        metadata: EventMetadata<T>,
        envelope: EventEnvelope<T>
    ): ProducerRecord<String, EventEnvelope<*>> {

        val key: String = metadata.partitionKey(envelope.data)

        @Suppress("UNCHECKED_CAST")
        val anyEnvelope: EventEnvelope<*> = envelope as EventEnvelope<*>

        return ProducerRecord(
            metadata.topic,
            key,
            anyEnvelope
        ).apply {
            headers().addString("eventId", envelope.eventId)
            headers().addString("eventType", envelope.eventType)
            envelope.parentEventId?.let { headers().addString("parentEventId", it) }
        }
    }

    // Helper for adding string headers safely
    private fun Headers.addString(key: String, value: String) {
        add(RecordHeader(key, value.toByteArray(StandardCharsets.UTF_8)))
    }
}
*/