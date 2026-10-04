package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.command.ProcessPaymentIntentUpdateCommand
import com.dogancaglar.paymentservice.domain.exception.PaymentIntentNotFoundException
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus
import com.dogancaglar.paymentservice.ports.inbound.usecases.UpdatePaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.outbound.PaymentIntentRepository
import org.slf4j.LoggerFactory

class UpdatePaymentIntentService(
    private val paymentIntentRepository: PaymentIntentRepository
) : UpdatePaymentIntentUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun processUpdate(cmd: ProcessPaymentIntentUpdateCommand): PaymentIntent {
        logger.debug("Processing payment intent update: id={}, status={}", cmd.paymentIntentId.value, cmd.status)

        val paymentIntent = paymentIntentRepository.findById(cmd.paymentIntentId)
            ?: throw PaymentIntentNotFoundException("PaymentIntent ${cmd.paymentIntentId.value} not found")

        return when (paymentIntent.status) {
            PaymentIntentStatus.CREATED_PENDING -> {
                if (cmd.status == PaymentIntentStatus.CREATED) {
                    val updated = paymentIntent.markAsCreatedWithPspReferenceAndClientSecret(
                        pspReference = cmd.pspReference,
                        clientSecret = cmd.clientSecret ?: ""
                    )
                    paymentIntentRepository.updatePaymentIntent(updated)
                    logger.debug("Updated PaymentIntent to CREATED: {}", cmd.paymentIntentId.value)
                    updated
                } else {
                    logger.warn("Ignoring invalid transition from CREATED_PENDING to {}", cmd.status)
                    paymentIntent
                }
            }
            PaymentIntentStatus.CREATED -> {
                // If we receive another CREATED event, just return existing (idempotency)
                if (cmd.status == PaymentIntentStatus.CREATED) {
                    logger.debug("PaymentIntent already CREATED: {}", cmd.paymentIntentId.value)
                    paymentIntent
                } else {
                    // Handle other transitions if needed (e.g. directly to AUTHORIZED? unlikely without PENDING_AUTH)
                    logger.warn("Ignoring transition from CREATED to {}", cmd.status)
                    paymentIntent
                }
            }
            else -> {
                logger.debug(
                    "PaymentIntent already in state {}, ignoring update to {}",
                    paymentIntent.status,
                    cmd.status
                )
                paymentIntent
            }
        }
    }
}
