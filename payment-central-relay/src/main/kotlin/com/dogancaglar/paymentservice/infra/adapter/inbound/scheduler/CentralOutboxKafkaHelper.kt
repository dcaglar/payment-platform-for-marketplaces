package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.kafka.publisher.RawEventPublisher
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.apache.kafka.common.KafkaException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

@Component
class CentralOutboxKafkaHelper(
    private val rawEventPublisher: RawEventPublisher
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @WithSpan("publish-outbox-event")
    fun processEntryAsync(entry: OutboxEvent): CompletableFuture<*> {
        return try {
            logger.debug(
                "🚀 CentralOutboxKafkaHelper: Processing outbox event oeid={} of type={}",
                entry.oeid,
                entry.eventType
            )
            rawEventPublisher.publishRaw(entry)
        } catch (e: KafkaException) {
            // a synchronous send failure (e.g. serialization, metadata timeout) becomes a failed future,
            // like the asynchronous ones
            CompletableFuture.failedFuture<Any>(e)
        }
    }
}
