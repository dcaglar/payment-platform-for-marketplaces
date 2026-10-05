package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka.consumers

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.kafka.metadata.ConsumerGroups
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.common.logging.EventLogContext
import com.dogancaglar.paymentservice.application.events.CaptureConfirmed
import com.dogancaglar.paymentservice.application.events.InternalTransferCommand
import com.dogancaglar.paymentservice.application.events.PaymentAuthorized
import com.dogancaglar.paymentservice.application.events.SettlementReceived
import com.dogancaglar.paymentservice.ports.inbound.usecases.ProcessPspResultUseCase
import com.dogancaglar.paymentservice.ports.outbound.EventDeduplicationPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * PspResultConsumer
 *
 * Mandate: Listens to psp-result-queue and delegates to ProcessPspResultProcessingService
 * to execute real financial state mutations in a transactional manner.
 */
@Component
class PspResultConsumer(
    private val processPspResultUseCase: ProcessPspResultUseCase,
    private val dedupe: EventDeduplicationPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [Topics.PSP_RESULTS],
        containerFactory = ConsumerGroups.PSP_RESULT_CONSUMER + "-factory",
        groupId = ConsumerGroups.PSP_RESULT_CONSUMER
    )
    fun onPspResult(
        record: ConsumerRecord<String, EventEnvelope<Event>>
    ) {
        val envelope = record.value()
        EventLogContext.with(envelope) {
            val eventId = envelope.data.deterministicEventId()
            if (dedupe.exists(ConsumerGroups.PSP_RESULT_CONSUMER, eventId)) {
                logger.warn("⚠️ Event is processed already, skipping eventId=$eventId")
                return@with
            }

            val event = record.value().data

            // default if it ist auth+capture
            when (event) {
                is PaymentAuthorized -> {
                    logger.debug(
                        "🎬 Processing PaymentAuthorized event for paymentIntentId: ${event.paymentIntentId}"
                    )
                    processPspResultUseCase.processAuthorized(event)
                }

                is CaptureConfirmed -> {
                    logger.debug(
                        "🎬 Processing CaptureConfirmed event for paymentIntentId: ${event.publicPaymentIntentId}"
                    )
                    processPspResultUseCase.processCaptureConfirmed(event)
                }

                is InternalTransferCommand -> {
                    logger.debug("🎬 Processing InternalTransferCommand event for target: ${event.targetAccount}")
                    processPspResultUseCase.processInternalTransferCommand(event)
                }

                is SettlementReceived -> {
                    logger.debug(
                        "🎬 Processing SettlementReceived event from simulated SDR line for paymentIntentId: " +
                            "${event.publicPaymentIntentId}"
                    )
                    processPspResultUseCase.processSettlementLineReconciled(event)
                }

                else -> {
                    logger.warn("⚠️ Unhandled event type in PspResultConsumer: ${event.javaClass.name}")
                }
            }

            logger.info("PSP result consumer executed successfully for event type=${event.javaClass.simpleName}")
            dedupe.markProcessed(
                ConsumerGroups.PSP_RESULT_CONSUMER,
                eventId,
                EventDeduplicationPort.PROCESSED_EVENT_TTL_SECONDS
            )
        }
    }
}
