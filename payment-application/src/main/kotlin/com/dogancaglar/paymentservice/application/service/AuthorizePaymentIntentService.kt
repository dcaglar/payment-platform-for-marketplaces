package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.application.command.AuthorizePaymentIntentCommand
import com.dogancaglar.paymentservice.application.events.PaymentAuthorized
import com.dogancaglar.paymentservice.domain.exception.PaymentIntentDomainException
import com.dogancaglar.paymentservice.domain.exception.PaymentNotReadyException
import com.dogancaglar.paymentservice.domain.exception.PaymentPlatformException
import com.dogancaglar.paymentservice.domain.exception.PspInvalidPaymentException
import com.dogancaglar.paymentservice.domain.exception.PspPermanentException
import com.dogancaglar.paymentservice.domain.exception.PspTransientException
import com.dogancaglar.paymentservice.domain.exception.PspUnknownException
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus
import com.dogancaglar.paymentservice.ports.inbound.usecases.AuthorizePaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import com.dogancaglar.paymentservice.ports.outbound.PaymentIntentRepository
import com.dogancaglar.paymentservice.ports.outbound.PaymentTransactionalFacadePort
import com.dogancaglar.paymentservice.ports.outbound.PspAuthorizationGatewayPort
import com.dogancaglar.paymentservice.ports.outbound.ResilientExecutionPort
import org.slf4j.LoggerFactory

/**
 * What the buyer's money is after authorize, and what we store:
 * - PSP answered AUTHORIZED / DECLINED / CANCELLED -> stored as answered (DECLINED is final)
 * - PSP answered "not decided yet" -> stays PENDING_AUTH (202)
 * - PSP refused our request for good (or the payment method cannot be sent) -> FAILED, final (422)
 * - PSP had a temporary problem, nothing was done (not sent, 429, 503) -> back to CREATED, the exception goes
 *   to the caller (503). Authorizing again is safe: every PSP call carries the same idempotency key for this
 *   payment, so the PSP authorizes it at most once.
 * - no usable answer (timeout, reset, 5xx, unreadable): the PSP may have authorized -> stays PENDING_AUTH (202),
 *   never reopened for another authorize while the outcome is unknown
 * - PSP answered but saving it failed -> stays PENDING_AUTH, returned as pending (202)
 * - any other error -> stays PENDING_AUTH, the exception goes to the caller
 */
class AuthorizePaymentIntentService(
    private val outboxEventFactoryPort: OutboxEventFactoryPort,
    private val paymentIntentRepository: PaymentIntentRepository,
    private val pspAuthGatewayPort: PspAuthorizationGatewayPort,
    private val resilientExecutionPort: ResilientExecutionPort,
    private val paymentTransactionalFacadePort: PaymentTransactionalFacadePort
) : AuthorizePaymentIntentUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun authorize(cmd: AuthorizePaymentIntentCommand): PaymentIntent {
        // only the caller's own intent: another merchant's is "not found" (404)
        val paymentIntent = paymentIntentRepository.findByIdForMerchant(cmd.paymentIntentId, cmd.merchantAccount)
            ?: throw PaymentIntentDomainException.PaymentIntentNotFoundException(
                "PaymentIntent ${cmd.paymentIntentId.value} not found"
            )
        // 1) Idempotent behavior first (NO domain transition before this)
        when (paymentIntent.status) {
            PaymentIntentStatus.CREATED_PENDING -> {
                // Payment not ready for authorization yet
                throw PaymentNotReadyException("Payment ${cmd.paymentIntentId.value} is not ready")
            }

            PaymentIntentStatus.AUTHORIZED,
            PaymentIntentStatus.DECLINED,
            PaymentIntentStatus.FAILED,
            PaymentIntentStatus.CANCELLED -> return paymentIntent

            PaymentIntentStatus.PENDING_AUTH -> return paymentIntent // controller maps to 202

            PaymentIntentStatus.CREATED -> {
                // continue
            }
        }
        // 2) Concurrency gate: only ONE request may flip CREATED -> PENDING_AUTH
        val now = Utc.nowInstant()
        val won = paymentIntentRepository.tryMarkPendingAuth(cmd.paymentIntentId, now)
        if (!won) {
            // someone else started authorization; return latest state
            return paymentIntentRepository.findById(cmd.paymentIntentId)
                ?: throw PaymentIntentDomainException.PaymentIntentNotFoundException(
                    "PaymentIntent ${cmd.paymentIntentId.value} not found"
                )
        }

        // 3) We "own" the authorization attempt; update in-memory state before psp call
        val authPendingPaymentIntent = paymentIntent.markAuthorizedPending(Utc.fromInstant(now))
        // No redundant DB update here - tryMarkPendingAuth already secured the state in the database

        // For Stripe Payment Element, paymentMethod is optional - payment method is already attached to PaymentIntent
        val result: PaymentIntent
        try {
            result = resilientExecutionPort.executeWithTimeoutAndBackgroundFallback(
                primaryTask = {
                    pspAuthGatewayPort.authorizePaymentIntent(authPendingPaymentIntent, cmd.paymentMethod)
                },
                timeoutMs = 3000,
                onTimeoutFallback = {
                    logger.info(
                        "Payment authorization timed out for {}, returning PENDING_AUTH and continuing in background",
                        paymentIntent.paymentIntentId.value
                    )
                    authPendingPaymentIntent // Returns PENDING_AUTH status, mapping to 202 in controller
                },
                onBackgroundSuccess = { backgroundResult -> saveResult(backgroundResult) },
                onBackgroundFailure = { error -> handleBackgroundFailure(authPendingPaymentIntent, error) }
            )
        } catch (e: PaymentPlatformException) {
            // grouped by outcome; the same rule as in the background (handleBackgroundFailure)
            when (e) {
                // refused for good, or this payment method can't be sent: the same answer every time
                is PspPermanentException, is PspInvalidPaymentException ->
                    return markFailed(authPendingPaymentIntent, e)
                // not done: authorizing again is safe (same idempotency key)
                is PspTransientException -> {
                    revertToCreated(authPendingPaymentIntent)
                    throw e
                }
                // maybe done: stays PENDING_AUTH (202) while the outcome is unknown
                is PspUnknownException -> {
                    logger.warn(
                        "Authorization of {} outcome unknown, left PENDING_AUTH",
                        authPendingPaymentIntent.paymentIntentId.value,
                        e
                    )
                    return authPendingPaymentIntent
                }
                // not a PSP answer (our own error): propagates, the intent stays PENDING_AUTH
                else -> throw e
            }
        }

        // 4) The PSP answered; store it. If storing fails, the PSP may have authorized: "still confirming" (202)
        return saveOrLeavePending(result, authPendingPaymentIntent)
    }

    /**
     * Stores the PSP's answer; if storing fails for any reason, the intent stays PENDING_AUTH
     * ("still confirming", 202).
     * Any failure on purpose: the repository port gives no typed error, and whatever it is, the decision is the same.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun saveOrLeavePending(result: PaymentIntent, authPendingPaymentIntent: PaymentIntent): PaymentIntent {
        try {
            saveResult(result)
            return result
        } catch (e: Exception) {
            logger.error(
                "PSP answered {} for {} but saving it failed, left PENDING_AUTH",
                result.status,
                authPendingPaymentIntent.paymentIntentId.value,
                e
            )
            return authPendingPaymentIntent
        }
    }

    /** Stores the PSP's answer. PENDING_AUTH (not decided yet) is already stored by tryMarkPendingAuth. */
    private fun saveResult(result: PaymentIntent) {
        when (result.status) {
            PaymentIntentStatus.AUTHORIZED -> handleAuthorizedPaymentResult(result)
            PaymentIntentStatus.DECLINED,
            PaymentIntentStatus.CANCELLED -> paymentIntentRepository.updatePaymentIntent(result)
            else -> {}
        }
    }

    /** Final: nothing was charged. Logged as error: a refusal of our request needs attention. */
    private fun markFailed(authPendingPaymentIntent: PaymentIntent, error: Throwable): PaymentIntent {
        logger.error(
            "PSP refused the authorization of {} for good, marking FAILED",
            authPendingPaymentIntent.paymentIntentId.value,
            error
        )
        val failed = authPendingPaymentIntent.markFailed()
        paymentIntentRepository.updatePaymentIntent(failed)
        return failed
    }

    /**
     * Back to CREATED: authorize can run again. Only for PspTransientException (nothing was done at the PSP); every
     * PSP call carries the same idempotency key for this payment, so the PSP authorizes it at most once.
     */
    private fun revertToCreated(authPendingPaymentIntent: PaymentIntent) {
        paymentIntentRepository.updatePaymentIntent(authPendingPaymentIntent.revertToCreated())
    }

    /** Runs after we already answered 202, so nobody else will log or handle this error. */
    private fun handleBackgroundFailure(authPendingPaymentIntent: PaymentIntent, error: Throwable) {
        when (error) {
            is PspPermanentException, is PspInvalidPaymentException -> markFailed(authPendingPaymentIntent, error)
            is PspTransientException -> {
                logger.warn(
                    "Background authorization for {} not done, back to CREATED",
                    authPendingPaymentIntent.paymentIntentId.value,
                    error
                )
                revertToCreated(authPendingPaymentIntent)
            }
            // maybe done: stays PENDING_AUTH while the outcome is unknown
            is PspUnknownException -> logger.warn(
                "Background authorization for {} outcome unknown, left PENDING_AUTH",
                authPendingPaymentIntent.paymentIntentId.value,
                error
            )
            // not a PSP answer (e.g. our database failed while saving it): leave PENDING_AUTH
            else -> logger.error(
                "Background authorization for {} failed, left PENDING_AUTH",
                authPendingPaymentIntent.paymentIntentId.value,
                error
            )
        }
    }

    private fun handleAuthorizedPaymentResult(confirmedPaymentIntent: PaymentIntent) {
        // generate outbox<paymentauthorized> +  from paymentintent objefct which is just authorized
        val paymentAuthorizedEvent = PaymentAuthorized.from(confirmedPaymentIntent, Utc.nowInstant())
        val outboxEventPaymentAuthorizedEvent = outboxEventFactoryPort.create(paymentAuthorizedEvent)
        paymentTransactionalFacadePort.handleAuthorized(confirmedPaymentIntent, outboxEventPaymentAuthorizedEvent)
    }
}
