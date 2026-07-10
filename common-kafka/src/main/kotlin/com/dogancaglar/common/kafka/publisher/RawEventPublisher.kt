package com.dogancaglar.common.kafka.publisher

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.metadata.EventMetaDataRegistry
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.ports.outbound.SerializationPort
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

class RawEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val eventMetaDataRegistry: EventMetaDataRegistry,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun publishRaw(
        outboxEvent: OutboxEvent,
    ): CompletableFuture<KafkaDeliveryResult> {

        // 1. Centralized Routing: The publisher is now the "Enforcer"
        val metadata = eventMetaDataRegistry.metadataFor<Event>(outboxEvent.eventType)


        val partitionKey = outboxEvent.partitionKey

        // 4. Build record
        val record = ProducerRecord(metadata.topic, outboxEvent.partitionKey, outboxEvent.payload).apply {
            outboxEvent.parentEventId?.let { headers().addString("parentEventId", outboxEvent.parentEventId) }
        }

        return kafkaTemplate.send(record)
            .thenApply { sendResult ->
                val meta = sendResult.recordMetadata
                KafkaDeliveryResult(meta.topic(), partitionKey, meta.offset())
            }
            .exceptionally { ex ->
                logger.error("❌ Failed to publish event type: ${outboxEvent.eventType}, key: $partitionKey", ex)
                throw ex
            }
    }


    private fun Headers.addString(key: String, value: String?) {
        add(RecordHeader(key, value?.toByteArray(StandardCharsets.UTF_8)))
    }
}