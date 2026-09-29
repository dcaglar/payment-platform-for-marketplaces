package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.command.CreatePaymentIntentCommand
import com.dogancaglar.paymentservice.domain.exception.PspPermanentException
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.ports.inbound.usecases.CreatePaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.outbound.IdGeneratorPort
import com.dogancaglar.paymentservice.ports.outbound.PaymentIntentRepository
import com.dogancaglar.paymentservice.ports.outbound.PspAuthorizationGatewayPort
import com.dogancaglar.paymentservice.ports.outbound.ResilientExecutionPort
import org.slf4j.LoggerFactory

/**
 * What we store after create:
 * - PSP created it -> CREATED (201)
 * - PSP slow -> CREATED_PENDING (202), the call continues in the background
 * - PSP refused to create it -> FAILED (final)
 * - any other error (not sent / temporary / unknown) -> stays CREATED_PENDING, the exception goes to the caller
 * - background call fails after the 202 -> FAILED, so the polling client stops waiting
 */
class CreatePaymentIntentService(
    private val paymentIntentRepository: PaymentIntentRepository,
    private val idGeneratorPort: IdGeneratorPort,
    private val pspAuthGatewayPort: PspAuthorizationGatewayPort,
    private val resilientExecutionPort: ResilientExecutionPort,
) : CreatePaymentIntentUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun create(cmd: CreatePaymentIntentCommand): PaymentIntent {
        // 1. Generate ID and create PaymentIntent
        val paymentIntentId = PaymentIntentId(idGeneratorPort.generateId())
        val paymentIntent = PaymentIntent.createNew(
            paymentIntentId = paymentIntentId,
            buyerId = cmd.buyerId,
            orderId = cmd.orderId,
            merchantAccount = cmd.merchantAccount,
            processingModel = cmd.processingModel,
            totalAmount = cmd.totalAmount,
            splits = cmd.paymentSplits
        )

        // 2. Save to database
        paymentIntentRepository.save(paymentIntent)

        // 3. Call the PSP, wait at most timeoutMs
        try {
            val createdPaymentIntent = resilientExecutionPort.executeWithTimeoutAndBackgroundFallback(
                primaryTask = {
                    pspAuthGatewayPort.createPaymentIntent(paymentIntent)
                },
                timeoutMs = 3000,
                onTimeoutFallback = {
                    logger.info(
                        "Payment creation timed out for {}, continuing in background",
                        paymentIntent.paymentIntentId.value
                    )
                    paymentIntent // Returns CREATED_PENDING status, mapping to 202 in controller
                },
                onBackgroundSuccess = { resultFromApi -> paymentIntentRepository.updatePaymentIntent(resultFromApi) },
                onBackgroundFailure = { error -> handleBackgroundFailure(paymentIntent, error) }
            )
            // on timeout the background call saves the answer; saving CREATED_PENDING here could overwrite it
            if (createdPaymentIntent.status != PaymentIntentStatus.CREATED_PENDING) {
                paymentIntentRepository.updatePaymentIntent(createdPaymentIntent)
            }
            return createdPaymentIntent
        } catch (e: PspPermanentException) {
            // the PSP refused to create it: a final answer, the intent is still CREATED_PENDING
            val failed = paymentIntent.markFailed()
            paymentIntentRepository.updatePaymentIntent(failed)
            return failed
        }
    }

    /** Runs after we already answered 202, so nobody else will log or handle this error. */
    private fun handleBackgroundFailure(paymentIntent: PaymentIntent, error: Throwable) {
        logger.error(
            "Background payment creation failed for {}, marking FAILED",
            paymentIntent.paymentIntentId.value, error
        )
        paymentIntentRepository.updatePaymentIntent(paymentIntent.markFailed())
    }
}
