package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.common.event.EventEnvelopeFactory
import com.dogancaglar.common.logging.EventLogContext
import com.dogancaglar.paymentservice.application.events.CaptureRequested
import com.dogancaglar.paymentservice.application.events.CaptureSubmitted
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.domain.model.payment.PspCaptureGatewayResponse
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.ports.inbound.usecases.ExecuteCaptureUseCase
import com.dogancaglar.paymentservice.ports.outbound.CentralOutboxWriterPort
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import com.dogancaglar.paymentservice.ports.outbound.PaymentRepository
import com.dogancaglar.paymentservice.ports.outbound.PspCaptureGatewayPort
import com.dogancaglar.paymentservice.ports.outbound.RetryQueuePort
import com.dogancaglar.paymentservice.ports.outbound.SerializationPort
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

open class ProcessCaptureService(
    private val pspCaptureGatewayPort: PspCaptureGatewayPort,
    private val paymentRepository: PaymentRepository,
    private val retryQueuePort: RetryQueuePort<CaptureRequested>,
    private val centralOutboxWriterPort: CentralOutboxWriterPort,
    private val outboxEventFactoryPort: OutboxEventFactoryPort,
    private val serializationPort: SerializationPort
) : ExecuteCaptureUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MAX_RETRIES = 5
        const val GATEWAY_TIMEOUT_MS = 2000L
    }

    override fun execute(captureRequested: CaptureRequested) {
        logger.debug("Executing network capture execution for paymentIntentId: \${captureRequested.publicPaymentIntentId}")

        try {
            val payment = paymentRepository.findByPaymentIntentId(PaymentIntentId(captureRequested.paymentIntentId.toLong()))
                ?: throw IllegalStateException("Payment context aggregate absent for intentId=\${captureRequested.paymentIntentId}")

            // 1. Dispatch network call safely outside of any active database context
            val responseFuture = pspCaptureGatewayPort.capture(payment)
            val pspResponse = responseFuture.get(GATEWAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)


            // 3.store an outbox event
            val outboxEvent = toOutboxCaptureSubmittedEvent(captureRequested, pspResponse)
            centralOutboxWriterPort.save(outboxEvent)

            logger.debug("Capture transaction state and outbox events safely persisted atomically.")

        } catch (e: Exception) {
            val actualCause = e.cause ?: e
            logger.error("❌ Exception encountered during PSP capture network execution layer for paymentIntentId: \${captureRequested.publicPaymentIntentId}", actualCause)
            handleRetry(captureRequested, actualCause.message)
        }
    }

    private fun handleRetry(event: CaptureRequested, lastError: String?) {
        val nextAttempt = event.attempt + 1
        if (nextAttempt > MAX_RETRIES) {
            logger.error("[RETRY-FAILURE] paymentIntentId={} reached max retries. Cause='{}'", event.publicPaymentIntentId, lastError ?: "UNKNOWN")
            return
        }
        val backoffMs = computeEqualJitterBackoff(nextAttempt)
        val retryEvent = event.withIncrementedAttempt()
        retryQueuePort.scheduleRetry(retryEvent, backoffMs)
    }

    private fun computeEqualJitterBackoff(attempt: Int, minDelayMs: Long = 2000L, maxDelayMs: Long = 60000L): Long {
        val exp = (minDelayMs * 2.0.pow((attempt - 1).coerceAtLeast(0))).toLong()
        val capped = min(exp, maxDelayMs)
        return capped / 2 + Random.Default.nextLong(capped / 2 + 1)
    }

    private fun toOutboxCaptureSubmittedEvent(captureRequested: CaptureRequested, pspCaptureGatewayResponse: PspCaptureGatewayResponse): OutboxEvent {
        val captureSubmittedEvent = CaptureSubmitted.from(captureRequested, pspCaptureGatewayResponse.pspReference)
        return outboxEventFactoryPort.create(captureSubmittedEvent)
    }
}
