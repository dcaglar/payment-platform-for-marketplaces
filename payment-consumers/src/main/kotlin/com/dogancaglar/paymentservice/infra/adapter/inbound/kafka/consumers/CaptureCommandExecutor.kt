package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka.consumers

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.kafka.metadata.ConsumerGroups
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.common.logging.EventLogContext
import com.dogancaglar.paymentservice.application.events.CaptureRequested
import com.dogancaglar.paymentservice.ports.inbound.usecases.ExecuteCaptureUseCase
import com.dogancaglar.paymentservice.ports.outbound.EventDeduplicationPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class CaptureCommandExecutor(
    private val executeCaptureUseCase: ExecuteCaptureUseCase,
    private val dedupe: EventDeduplicationPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MAX_RETRIES = 5
        const val GATEWAY_TIMEOUT_MS = 2000L
    }

    @KafkaListener(
        topics = [Topics.CAPTURE_REQUESTED],
        containerFactory = ConsumerGroups.CAPTURE_COMMAND_EXECUTOR + "-factory",
        groupId = ConsumerGroups.CAPTURE_COMMAND_EXECUTOR
    )
    fun consume(record: ConsumerRecord<String, EventEnvelope<CaptureRequested>>) {
        val envelope = record.value()
        EventLogContext.with(envelope) {
            val eventId = envelope.data.deterministicEventId()
            if (dedupe.exists(ConsumerGroups.CAPTURE_COMMAND_EXECUTOR, eventId)) {
                logger.warn("⚠️ Event is processed already, skipping eventId=\$eventId")
                return@with
            }

            val captureRequested = envelope.data
            executeCaptureUseCase.execute(captureRequested)
            dedupe.markProcessed(
                ConsumerGroups.CAPTURE_COMMAND_EXECUTOR,
                eventId,
                EventDeduplicationPort.PROCESSED_EVENT_TTL_SECONDS
            )
            logger.info(
                "Capture command executor executed successfully for " +
                    "paymentIntentId=${captureRequested.publicPaymentIntentId}"
            )
        }
    }
}
