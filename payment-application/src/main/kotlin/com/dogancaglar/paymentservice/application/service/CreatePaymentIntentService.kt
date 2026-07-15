package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.command.CreatePaymentIntentCommand
import com.dogancaglar.paymentservice.domain.exception.PspPermanentException
import com.dogancaglar.paymentservice.domain.exception.PspTransientException
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.ports.inbound.usecases.CreatePaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.outbound.IdGeneratorPort
import com.dogancaglar.paymentservice.ports.outbound.PaymentIntentRepository
import com.dogancaglar.paymentservice.ports.outbound.PspAuthorizationGatewayPort
import com.dogancaglar.paymentservice.ports.outbound.ResilientExecutionPort
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletionException
import java.util.concurrent.RejectedExecutionException

class CreatePaymentIntentService(
    private val paymentIntentRepository: PaymentIntentRepository,
    private val idGeneratorPort: IdGeneratorPort,
    private val pspAuthGatewayPort: PspAuthorizationGatewayPort,
    private val resilientExecutionPort: ResilientExecutionPort,
) : CreatePaymentIntentUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun create(cmd: CreatePaymentIntentCommand): PaymentIntent {
        // 1. Generate ID and create PaymentIntent
        logger.debug("CreatePaymentIntentService.create took started")
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
        val startSave = System.currentTimeMillis()
        paymentIntentRepository.save(paymentIntent)
        val finishSave = System.currentTimeMillis()
        logger.debug("paymentIntentRepository.savePaymentIntent TOOK {} MS", finishSave - startSave)

        // 3. Create async task to be executed(actual stripe call
        /*
        /*Returns a new CompletableFuture<PaymentIntent> that is asynchronously completed by a task( callCreatePaymentIntentApi(paymentIntent)) running in the given
         executor(pspAuthExecutor) with the value obtained by calling the given Supplier.
        */
         */
        // Wait with timeout
        return try {
            val startPspCall = System.currentTimeMillis()
            val createdPaymentIntent = resilientExecutionPort.executeWithTimeoutAndBackgroundFallback(
                primaryTask ={
                  pspAuthGatewayPort.createPaymentIntent(paymentIntent)
                }, // tsk to be run aysnc by thread pool passed
                timeoutMs = 3000,
                onTimeoutFallback = {
                    logger.debug(
                        "Payment creation timed out for {}, continuing in background",
                        paymentIntent.paymentIntentId.value
                    )
                    paymentIntent // Returns CREATED_PENDING status, mapping to 202 in controller
                },
                onBackgroundSuccess = { resultFromApi ->
                    handleBackgroundPaymentIntentCreationSuccess(
                        resultFromApi
                    )
                },
                onBackgroundFailure = { error -> handleBackgroundFailure(paymentIntent, error) }
            )
            val finishSPspCall = System.currentTimeMillis()
           logger.debug("Stripe createPaymentIntent tTOOK  {} MS", finishSPspCall - startPspCall)
            val startUpdatePaymentIntent = System.currentTimeMillis()
            paymentIntentRepository.updatePaymentIntent(createdPaymentIntent)
            val finishUpdatePaymentIntent = System.currentTimeMillis()
                logger.debug("db.updatePaymentIntent TOOK {} MS", finishUpdatePaymentIntent - startUpdatePaymentIntent)
            createdPaymentIntent
        } catch (e: Exception){
            handleImmediateFailure(paymentIntent,e)
        }
    }

    //a callable

    private fun handleBackgroundPaymentIntentCreationSuccess(successfulPaymentIntent: PaymentIntent) {
        logger.debug("Background payment intent creation succesful for ${successfulPaymentIntent.paymentIntentId.value}, promooting to status created and psp reference from stripe:${successfulPaymentIntent.pspReferenceOrThrow()}")
        val startUpdate = System.currentTimeMillis()
        paymentIntentRepository.updatePaymentIntent(successfulPaymentIntent)
        val finishUpdate = System.currentTimeMillis()
        logger.debug("db.updatePaymentIntent (success) TOOK  {} MS", finishUpdate - startUpdate)
    }


    private fun handleBackgroundFailure(paymentIntent: PaymentIntent, error: Throwable) {
        logger.error("Background payment creation failed for ${paymentIntent.paymentIntentId.value}", error)
        if (error is PspPermanentException || error !is PspTransientException) {
            //cancel if we get exception when it waits for stripe response, and if it is psp permanent
            val canceledPaymentIntent = paymentIntent.markCancelled()
            val startUpdate = System.currentTimeMillis()
            paymentIntentRepository.updatePaymentIntent(canceledPaymentIntent)
            val finishUpdate = System.currentTimeMillis()
            logger.debug("paymentIntentRepository.updatePaymentIntent (failure) TOOK {} MS", finishUpdate - startUpdate)
        }
    }

    private fun handleImmediateFailure(paymentIntent: PaymentIntent, error: Throwable): PaymentIntent {
        logger.error("handle imeatiate failure :" + error)
        val cause = if (error is CompletionException) error.cause ?: error else error
        return when (cause) {
            is PspTransientException -> {
                logger.warn("Transient failure during payment creation for {}: {}", paymentIntent.paymentIntentId.value, cause.message)
                paymentIntent
            }
            is RejectedExecutionException -> {
                logger.warn("PSP thread pool saturated for {}, returning pending status (backpressure)", paymentIntent.paymentIntentId.value)
                paymentIntent // Return CREATED_PENDING status, mapping to 202 in controller
            }
            is PspPermanentException -> {
                logger.error("Permanent failure during payment creation for {}: {}", paymentIntent.paymentIntentId.value, cause.message)
                val cancelled = paymentIntent.markCancelled()
                val startUpdate = System.currentTimeMillis()
                paymentIntentRepository.updatePaymentIntent(cancelled)
                val finishUpdate = System.currentTimeMillis()
                logger.debug("db.updatePaymentIntent (immediate failure) TOOK {} MS", finishUpdate - startUpdate)
                cancelled
            }
            else -> {
                logger.error("Unexpected failure during payment creation for {}", paymentIntent.paymentIntentId.value, cause)
                throw RuntimeException("Unexpected failure", cause)
            }
        }
    }
}
