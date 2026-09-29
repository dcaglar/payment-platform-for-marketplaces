package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.command.GetPaymentIntentCommand
import com.dogancaglar.paymentservice.domain.exception.PaymentIntentNotFoundException
import com.dogancaglar.paymentservice.domain.exception.PspTransientException
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus
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
        val paymentIntent = paymentIntentRepository.findById(cmd.paymentIntentId)
            ?: throw PaymentIntentNotFoundException("PaymentIntent ${cmd.paymentIntentId.value} not found")

        // The client secret (never persisted) is only needed to show the card form, i.e. while CREATED.
        // Every other status is answered from our database only, so polling does not add PSP calls.
        if (paymentIntent.status != PaymentIntentStatus.CREATED) {
            return paymentIntent
        }

        // PSP errors go to the caller: without the secret the card form cannot be shown
        val clientSecret = resilientExecutionPort.executeWithTimeoutAndBackgroundFallback(
            primaryTask = {
                pspAuthGatewayPort.retrieveClientSecret(paymentIntent.pspReferenceOrThrow())!!
            },
            timeoutMs = 2000,
            onTimeoutFallback = {
                throw PspTransientException(
                    "Timed out retrieving the client secret for ${cmd.paymentIntentId.value}", null
                )
            },
            // a late answer is not needed: the client retries the GET
            onBackgroundSuccess = { },
            onBackgroundFailure = { error ->
                logger.warn(
                    "Late client secret retrieval failed for {}: {}",
                    cmd.paymentIntentId.value, error.message
                )
            }
        )
        return paymentIntent.withClientSecret(clientSecret)
    }
}
