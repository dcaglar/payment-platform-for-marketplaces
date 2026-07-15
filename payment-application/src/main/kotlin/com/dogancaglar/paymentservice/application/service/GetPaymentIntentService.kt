package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.command.GetPaymentIntentCommand
import com.dogancaglar.paymentservice.domain.exception.PaymentIntentNotFoundException
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.ports.inbound.usecases.GetPaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.outbound.PaymentIntentRepository
import com.dogancaglar.paymentservice.ports.outbound.PspAuthorizationGatewayPort
import com.dogancaglar.paymentservice.ports.outbound.ResilientExecutionPort

import org.slf4j.LoggerFactory

class GetPaymentIntentService(
    private val paymentIntentRepository: PaymentIntentRepository,
    private val pspAuthGatewayPort: PspAuthorizationGatewayPort,
    private val resilientExecutionPort: ResilientExecutionPort
) : GetPaymentIntentUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getPaymentIntent(cmd: GetPaymentIntentCommand): PaymentIntent {
        logger.debug("GetPaymentIntentService.getPaymentIntent started")
        val start = System.currentTimeMillis()
        // Load PaymentIntent from database
        val paymentIntent = paymentIntentRepository.findById(cmd.paymentIntentId)
            ?: throw PaymentIntentNotFoundException("PaymentIntent ${cmd.paymentIntentId.value} not found")
        val finish = System.currentTimeMillis()
        logger.debug("paymentIntentRepository.findById TOOK {} MS", finish - start)

        // If pspReference exists, retrieve clientSecret from Stripe (never persisted)
        if (paymentIntent.hasPspReference()) {
            return try {
                val startPspCall = System.currentTimeMillis()
                val clientSecret = resilientExecutionPort.executeWithTimeoutAndBackgroundFallback(
                    primaryTask = {
                        pspAuthGatewayPort.retrieveClientSecret(paymentIntent.pspReferenceOrThrow())!!
                    },
                    timeoutMs = 2000,
                    onTimeoutFallback = {
                        logger.warn("Timeout retrieving clientSecret from Stripe for pspReference={}", paymentIntent.pspReference)
                        ""
                    },
                    onBackgroundSuccess = { },
                    onBackgroundFailure = { }
                )
                val finishPspCall = System.currentTimeMillis()
                logger.debug("Stripe retrieveClientSecret TOOK {} MS", finishPspCall - startPspCall)

                if (clientSecret.isNotEmpty()) {
                    logger.debug("Retrieved clientSecret from Stripe for paymentIntentId={}", cmd.paymentIntentId.value)
                    paymentIntent.withClientSecret(clientSecret)
                } else {
                    logger.warn("Could not retrieve clientSecret from Stripe for pspReference={}", paymentIntent.pspReference)
                    paymentIntent
                }
            } catch (e: Exception) {
                logger.error("Error retrieving client secret", e)
                paymentIntent
            }
        } else {
            logger.debug("No pspReference found for paymentIntentId={}, skipping Stripe retrieval", cmd.paymentIntentId.value)
            return paymentIntent
        }
    }
}

