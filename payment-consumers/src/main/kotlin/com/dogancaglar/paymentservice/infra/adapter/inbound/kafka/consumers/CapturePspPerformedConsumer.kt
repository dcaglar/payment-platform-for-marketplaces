package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka.consumers

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.kafka.metadata.CONSUMER_GROUPS
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.common.logging.EventLogContext
import com.dogancaglar.paymentservice.application.events.CaptureSubmitted
import com.dogancaglar.paymentservice.application.service.RecordCaptureSubmissionService
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

import com.dogancaglar.paymentservice.ports.outbound.EventDeduplicationPort

@Component
class CapturePspPerformedConsumer(
    private val objectMapper: ObjectMapper,
    private val recordCaptureSubmissionService: RecordCaptureSubmissionService,
    private val dedupe: EventDeduplicationPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [Topics.CAPTURE_SUBMITTED_ACKS],
        containerFactory = CONSUMER_GROUPS.CAPTURE_SUBMITTED_CONSUMER + "-factory",
        groupId = CONSUMER_GROUPS.CAPTURE_SUBMITTED_CONSUMER
    )
    fun consume(record: ConsumerRecord<String, EventEnvelope<CaptureSubmitted>>) {
        val envelope = record.value()
        EventLogContext.with(envelope) {
            val eventId = envelope.data.deterministicEventId()
            if (dedupe.exists(eventId)) {
                logger.warn("⚠️ Event is processed already, skipping eventId=\$eventId")
                return@with
            }

            val eventData = envelope.data
            logger.debug("Consuming capture PSP performed event for payment: \${eventData.publicPaymentIntentId}")

            try {
                recordCaptureSubmissionService.recordSubmission(
                    event = eventData,
                    parentEventId = envelope.eventId
                )
                dedupe.markProcessed(eventId, 3600)
                logger.info("Capture PSP performed consumer executed successfully for paymentIntentId=${eventData.publicPaymentIntentId}")
            } catch (e: Exception) {
                logger.error("❌ Failed to process capture PSP performed event", e)
                throw e
            }
        }
    }
}