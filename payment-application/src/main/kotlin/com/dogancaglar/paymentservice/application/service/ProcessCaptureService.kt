package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.events.CaptureRequested
import com.dogancaglar.paymentservice.application.events.CaptureSubmitted
import com.dogancaglar.paymentservice.domain.exception.PaymentDomainException
import com.dogancaglar.paymentservice.domain.exception.PaymentPlatformException
import com.dogancaglar.paymentservice.domain.exception.PspOperation
import com.dogancaglar.paymentservice.domain.exception.PspPermanentException
import com.dogancaglar.paymentservice.domain.exception.PspTransientException
import com.dogancaglar.paymentservice.domain.exception.PspUnknownException
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.domain.model.payment.Payment
import com.dogancaglar.paymentservice.domain.model.payment.PspCaptureGatewayResponse
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.ports.inbound.usecases.ExecuteCaptureUseCase
import com.dogancaglar.paymentservice.ports.outbound.CentralOutboxWriterPort
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import com.dogancaglar.paymentservice.ports.outbound.PaymentRepository
import com.dogancaglar.paymentservice.ports.outbound.PspCaptureGatewayPort
import com.dogancaglar.paymentservice.ports.outbound.RetryQueuePort
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

open class ProcessCaptureService(
    private val pspCaptureGatewayPort: PspCaptureGatewayPort,
    private val paymentRepository: PaymentRepository,
    private val retryQueuePort: RetryQueuePort<CaptureRequested>,
    private val centralOutboxWriterPort: CentralOutboxWriterPort,
    private val outboxEventFactoryPort: OutboxEventFactoryPort
) : ExecuteCaptureUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MAX_RETRIES = 5
        const val GATEWAY_TIMEOUT_MS = 2000L
    }

    override fun execute(captureRequested: CaptureRequested) {
        logger.debug("Executing network capture for paymentIntentId={}", captureRequested.publicPaymentIntentId)

        // our own errors (payment missing, database) are not PSP answers: they propagate, and the Kafka error
        // handler retries the event or sends it to the DLQ
        val paymentIntentId = PaymentIntentId(captureRequested.paymentIntentId.toLong())
        val payment = paymentRepository.findByPaymentIntentId(paymentIntentId)
            ?: throw PaymentDomainException.PaymentNotFoundException("paymentIntentId=${paymentIntentId.value}")

        // null: the PSP gave no normal answer, handled here (retry later, or refused for good)
        val pspResponse: PspCaptureGatewayResponse? = try {
            capture(payment)
        } catch (e: PaymentPlatformException) {
            when (e) {
                // not done, or outcome unknown: capture again later (same idempotency key)
                is PspTransientException, is PspUnknownException -> {
                    scheduleRetry(captureRequested, e)
                    null
                }
                is PspPermanentException -> {
                    logger.error(
                        "PSP refused the capture of {} for good, not retrying",
                        captureRequested.publicPaymentIntentId,
                        e
                    )
                    null
                }
                // not a PSP answer (our own error): propagates, the Kafka error handler retries or DLQs the event
                else -> throw e
            }
        }
        if (pspResponse == null) {
            return
        }

        centralOutboxWriterPort.save(toOutboxCaptureSubmittedEvent(captureRequested, pspResponse))
        logger.debug("Capture of {} submitted, outbox event saved", captureRequested.publicPaymentIntentId)
    }

    /**
     * Calls the PSP and waits at most GATEWAY_TIMEOUT_MS. Throws the adapter's own exception, not the future's
     * ExecutionException wrapper.
     */
    @Suppress("ThrowsCount") // timeout, the adapter's exception, and an interrupt each need their own throw
    private fun capture(payment: Payment): PspCaptureGatewayResponse {
        val responseFuture = pspCaptureGatewayPort.capture(payment)
        try {
            return responseFuture.get(GATEWAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // the request may have reached the PSP: we don't know
            throw PspUnknownException(
                PspOperation.CAPTURE,
                payment.paymentIntentId.value,
                "no answer within $GATEWAY_TIMEOUT_MS ms",
                e
            )
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /** Not done, or outcome unknown: capture again later (the PSP call carries the same idempotency key). */
    private fun scheduleRetry(captureRequested: CaptureRequested, error: RuntimeException) {
        logger.warn(
            "Capture of {} not done or outcome unknown, scheduling a retry",
            captureRequested.publicPaymentIntentId,
            error
        )
        handleRetry(captureRequested, error.message)
    }

    private fun handleRetry(event: CaptureRequested, lastError: String?) {
        val nextAttempt = event.attempt + 1
        if (nextAttempt > MAX_RETRIES) {
            logger.error(
                "[RETRY-FAILURE] paymentIntentId={} reached max retries. Cause='{}'",
                event.publicPaymentIntentId,
                lastError ?: "UNKNOWN"
            )
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
