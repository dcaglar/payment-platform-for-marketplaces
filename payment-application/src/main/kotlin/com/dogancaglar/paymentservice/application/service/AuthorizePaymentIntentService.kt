package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.application.command.AuthorizePaymentIntentCommand
import com.dogancaglar.paymentservice.application.events.PaymentAuthorized
import com.dogancaglar.paymentservice.domain.exception.PaymentNotReadyException
import com.dogancaglar.paymentservice.domain.exception.PspPermanentException
import com.dogancaglar.paymentservice.domain.exception.PspTransientException
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus

import com.dogancaglar.paymentservice.ports.inbound.usecases.AuthorizePaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.outbound.*
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletionException
import java.util.concurrent.RejectedExecutionException

class AuthorizePaymentIntentService(
    private val idGeneratorPort: IdGeneratorPort,
    private  val outboxEventFactoryPort: OutboxEventFactoryPort,
    private val paymentIntentRepository: PaymentIntentRepository,
    private val pspAuthGatewayPort: PspAuthorizationGatewayPort,
    private val resilientExecutionPort: ResilientExecutionPort,
    private val serializationPort: SerializationPort,
    private val paymentTransactionalFacadePort: PaymentTransactionalFacadePort
) : AuthorizePaymentIntentUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)
    override fun authorize(cmd: AuthorizePaymentIntentCommand): PaymentIntent {
        logger.debug("AuthorizePaymentIntentService.authorize  started for long numeric paymentintentid ,${cmd.paymentIntentId.value}")
        val start = System.currentTimeMillis()
        val paymentIntent = paymentIntentRepository.findById(cmd.paymentIntentId)
            ?: error("PaymentIntent ${cmd.paymentIntentId.value} not found")
        val finish = System.currentTimeMillis()
        // 1) Idempotent behavior first (NO domain transition before this)
        logger.debug("AuthorizePaymentIntentService.findbyId(${cmd.paymentIntentId.value} TOOK ${finish-start} MSreturned a $paymentIntent)")
        when (paymentIntent.status) {
            PaymentIntentStatus.CREATED_PENDING -> {
                // Payment not ready for authorization yet
                throw PaymentNotReadyException("Payment ${cmd.paymentIntentId.value} is not ready")
            }

            PaymentIntentStatus.AUTHORIZED,
            PaymentIntentStatus.DECLINED,
            PaymentIntentStatus.CANCELLED -> return paymentIntent

            PaymentIntentStatus.PENDING_AUTH -> return paymentIntent // controller maps to 202

            PaymentIntentStatus.CREATED -> {
                // continue
            }
        }
        // 2) Concurrency gate: only ONE request may flip CREATED -> PENDING_AUTH
        val now = Utc.nowInstant()
        val start2 = System.currentTimeMillis()
        val won = paymentIntentRepository.tryMarkPendingAuth(cmd.paymentIntentId, now)
        val end2 = System.currentTimeMillis()
        logger.debug("What is win returns $won and paymentIntentRepository.tryMarkPendingAuth( TOOK ${end2 - start2} MS")
        if (!won) {
            // someone else started authorization; return latest state
            val start1 = System.currentTimeMillis()
            return paymentIntentRepository.findById(cmd.paymentIntentId)
                ?: error("PaymentIntent not found ${cmd.paymentIntentId.value}")
            val end1 = System.currentTimeMillis()
            logger.debug("paymentIntentRepositoryRepository.findByI TOOK ${end1 - start1}  MS")



        }

        // 3) We "own" the authorization attempt; update in-memory state before psp call
        val authPendingPaymentIntent = paymentIntent.markAuthorizedPending(Utc.fromInstant(now))
        // No redundant DB update here - tryMarkPendingAuth already secured the state in the database

        //call the actual psp
        // For Stripe Payment Element, paymentMethod is optional - payment method is already attached to PaymentIntent
        return try {
            val startPspCall = System.currentTimeMillis()
            logger.debug("📡 [AuthorizeService] Initiating resilient PSP authorization for ${cmd.paymentIntentId.value}")
            val finalPaymentIntent = resilientExecutionPort.executeWithTimeoutAndBackgroundFallback(
                primaryTask ={
                    pspAuthGatewayPort.authorizePaymentIntent(authPendingPaymentIntent,cmd.paymentMethod)
                },
                timeoutMs = 3000,
                onTimeoutFallback = {
                    logger.warn(
                        "Payment authorization timed out for {}, returning PENDING_AUTH and continuing in background",
                        paymentIntent.paymentIntentId.value
                    )
                    authPendingPaymentIntent // Returns PENDING_AUTH status, mapping to 202 in controller
                },
                onBackgroundSuccess = { resultFromCallStripeApi ->
                    handleBackgroundPaymentIntentAuthorizationSuccess(
                        resultFromCallStripeApi
                    )
                },
                onBackgroundFailure = { error -> handleBackgroundFailure(paymentIntent, error) }
            )
            val finishPspCall = System.currentTimeMillis()
            logger.debug("Authorize took {} ms", finishPspCall - startPspCall)
            if (finalPaymentIntent.status == PaymentIntentStatus.AUTHORIZED) {
                handleAuthorizedPaymentResult(finalPaymentIntent)
            }
            finalPaymentIntent
        } catch (e: Exception){
            handleImmediateFailure(paymentIntent,e)
        }
    }



    private fun handleBackgroundPaymentIntentAuthorizationSuccess(authorizedPaymentIntent: PaymentIntent) {
        logger.debug("Background payment intent authorization successful for ${authorizedPaymentIntent.paymentIntentId.value}, promoting to status authorized and generating orders")
        handleAuthorizedPaymentResult(authorizedPaymentIntent)
    }


    private fun handleBackgroundFailure(paymentIntent: PaymentIntent, error: Throwable) {
        logger.error("Background payment intent authorization failed for ${paymentIntent.paymentIntentId.value} , mark as declined", error)
        if (error is PspPermanentException || error !is PspTransientException) {
            //decline it
            val canceledPaymentIntent = paymentIntent.markDeclined()
            val startUpdate = System.currentTimeMillis()
            paymentIntentRepository.updatePaymentIntent(canceledPaymentIntent)
            val finishUpdate = System.currentTimeMillis()
            logger.debug("db.updatePaymentIntent (failure) took {} ms", finishUpdate - startUpdate)
        }
    }

    private fun handleImmediateFailure(paymentIntent: PaymentIntent, error: Throwable): PaymentIntent {
        logger.error("Immediate exceptiuon occurred  :$error")
        val cause = if (error is CompletionException) error.cause ?: error else error
        return when (cause) {
            is PspTransientException -> {
                logger.warn("Transient failure during payment authorization for ${paymentIntent.paymentIntentId.value} : ${cause.message} ")
                paymentIntent
            }
            is RejectedExecutionException -> {
                logger.warn("PSP thread pool saturated for ${paymentIntent.paymentIntentId.value}, returning pending status (backpressure)")
                paymentIntent // Return CREATED_PENDING status, mapping to 202 in controller
            }
            is PspPermanentException -> {
                logger.error("Permanent failure during payment intent authorization for ${paymentIntent.paymentIntentId.value} , marking as DECLINED", cause)
                val cancelled = paymentIntent.markDeclined()
                val startUpdate = System.currentTimeMillis()
                paymentIntentRepository.updatePaymentIntent(cancelled)
                val finishUpdate = System.currentTimeMillis()
                logger.debug("db.updatePaymentIntent (immediate failure) TOOK {} MS", finishUpdate - startUpdate)
                cancelled
            }
            else -> {
                logger.error("Unexpected failure during payment intent authoirzation for {}", paymentIntent.paymentIntentId.value, cause)
                throw RuntimeException("Unexpected failure", cause)
            }
        }
    }




    private fun handleAuthorizedPaymentResult(confirmedPaymentIntent : PaymentIntent){
        if(confirmedPaymentIntent.status == PaymentIntentStatus.AUTHORIZED){
            //generate outbox<paymentauthorized> +  from paymentintent objefct which is just authorized
            val paymentAuthorizedEvent = PaymentAuthorized.from(confirmedPaymentIntent,Utc.nowInstant())
            val outboxEventPaymentAuthorizedEvent = outboxEventFactoryPort.create(paymentAuthorizedEvent)
            val startUpdate = System.currentTimeMillis()
            paymentTransactionalFacadePort.handleAuthorized(confirmedPaymentIntent,outboxEventPaymentAuthorizedEvent)
            val finishUpdate = System.currentTimeMillis()
            logger.debug("paymentTransactionalFacadePort.handleAuthorize TOOK{} MS", finishUpdate - startUpdate)
        }
    }


}