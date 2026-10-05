package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka.consumers

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.kafka.metadata.ConsumerGroups
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.common.logging.EventLogContext
import com.dogancaglar.paymentservice.application.events.CaptureSubmitted
import com.dogancaglar.paymentservice.application.service.RecordCaptureSubmissionService
import com.dogancaglar.paymentservice.ports.outbound.EventDeduplicationPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class CapturePspPerformedConsumer(
    private val recordCaptureSubmissionService: RecordCaptureSubmissionService,
    private val dedupe: EventDeduplicationPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [Topics.CAPTURE_SUBMITTED_ACKS],
        containerFactory = ConsumerGroups.CAPTURE_SUBMITTED_CONSUMER + "-factory",
        groupId = ConsumerGroups.CAPTURE_SUBMITTED_CONSUMER
    )
    fun consume(record: ConsumerRecord<String, EventEnvelope<CaptureSubmitted>>) {
        val envelope = record.value()
        EventLogContext.with(envelope) {
            val eventId = envelope.data.deterministicEventId()
            if (dedupe.exists(ConsumerGroups.CAPTURE_SUBMITTED_CONSUMER, eventId)) {
                logger.warn("⚠️ Event is processed already, skipping eventId=\$eventId")
                return@with
            }

            val eventData = envelope.data
            logger.debug("Consuming capture PSP performed event for payment: ${eventData.publicPaymentIntentId}")

            recordCaptureSubmissionService.recordSubmission(
                event = eventData,
                parentEventId = envelope.eventId
            )
            dedupe.markProcessed(
                ConsumerGroups.CAPTURE_SUBMITTED_CONSUMER,
                eventId,
                EventDeduplicationPort.PROCESSED_EVENT_TTL_SECONDS
            )
            logger.info(
                "Capture PSP performed consumer executed successfully for " +
                    "paymentIntentId=${eventData.publicPaymentIntentId}"
            )
        }
    }
}
