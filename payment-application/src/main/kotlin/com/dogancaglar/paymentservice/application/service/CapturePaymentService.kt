package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.command.CapturePaymentCommand
import com.dogancaglar.paymentservice.application.events.CaptureRequested
import com.dogancaglar.paymentservice.domain.exception.PaymentIntentNotFoundException
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.ports.inbound.usecases.CapturePaymentUseCase
import com.dogancaglar.paymentservice.ports.outbound.LocalOutboxWriterPort
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import com.dogancaglar.paymentservice.ports.outbound.PaymentIntentRepository
import org.slf4j.LoggerFactory

class CapturePaymentService(
    private val localOutboxWriterPort: LocalOutboxWriterPort,
    private val outboxEventFactoryPort: OutboxEventFactoryPort,
    private val paymentIntentRepository: PaymentIntentRepository
) : CapturePaymentUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun capture(cmd: CapturePaymentCommand): PaymentIntent {
        logger.debug("CapturePaymentService.capture started for paymentIntentId=${cmd.paymentIntentId.value}")

        val paymentIntent = paymentIntentRepository.findById(cmd.paymentIntentId)
            ?: throw PaymentIntentNotFoundException("PaymentIntent not found for ${cmd.paymentIntentId.value}")

        val captureEvent = CaptureRequested.from(
            paymentIntent = paymentIntent,
            captureAmount = cmd.amount
        )

        val outboxEvent = outboxEventFactoryPort.create(captureEvent)

        localOutboxWriterPort.saveAll(listOf(outboxEvent))

        return paymentIntent
    }
}
