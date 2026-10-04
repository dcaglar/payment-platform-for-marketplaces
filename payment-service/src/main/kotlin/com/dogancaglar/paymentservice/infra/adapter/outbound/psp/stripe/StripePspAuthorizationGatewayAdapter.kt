package com.dogancaglar.paymentservice.infra.adapter.outbound.psp.stripe

import com.dogancaglar.paymentservice.domain.exception.PspInvalidPaymentException
import com.dogancaglar.paymentservice.domain.exception.PspPermanentException
import com.dogancaglar.paymentservice.domain.exception.PspTransientException
import com.dogancaglar.paymentservice.domain.exception.PspUnknownException
import com.dogancaglar.paymentservice.domain.model.payment.CardSummary
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentMethod
import com.dogancaglar.paymentservice.infra.adapter.outbound.psp.PspCardSummary
import com.dogancaglar.paymentservice.ports.outbound.PspAuthorizationGatewayPort
import com.stripe.StripeClient
import com.stripe.exception.ApiConnectionException
import com.stripe.exception.CardException
import com.stripe.exception.StripeException
import com.stripe.net.RequestOptions
import com.stripe.param.PaymentIntentConfirmParams
import com.stripe.param.PaymentIntentCreateParams
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.net.SocketTimeoutException
import java.util.concurrent.*

@Component
@ConditionalOnProperty(name = ["psp.gateway.type"], havingValue = "STRIPE", matchIfMissing = true)
class StripePspAuthorizationGatewayAdapter(
    private val stripeClient: StripeClient,
    @param:Qualifier("createPaymentIntentExecutor") private val createPaymentIntentExecutor: ThreadPoolTaskExecutor,
    @param:Qualifier(
        "authorizePaymentIntentExecutor"
    ) private val authorizePaymentIntentExecutor: ThreadPoolTaskExecutor
) : PspAuthorizationGatewayPort {

    private val logger = LoggerFactory.getLogger(javaClass)


    override fun createPaymentIntent(paymentIntent: PaymentIntent): CompletableFuture<PaymentIntent> {
        // do not execute this in request thread, but hand the task to be executed in pspAuthExecutor
        /*Returns a new CompletableFuture that is asynchronously completed by a task( callCreatePaymentIntentApi(paymentIntent)) running in the given
         executor(pspAuthExecutor) with the value obtained by calling the given Supplier.
         */
        return submit(createPaymentIntentExecutor) {
            callCreatePaymentIntentApi(paymentIntent)
        }
    }

    override fun authorizePaymentIntent(paymentIntent: PaymentIntent, token: PaymentMethod?): CompletableFuture<PaymentIntent> {
        return submit(authorizePaymentIntentExecutor) {
            callConfirmPaymentIntentApi(paymentIntent, token)
        }
    }

    private fun callCreatePaymentIntentApi(paymentIntent: PaymentIntent): PaymentIntent {
        val idempotencyKey = "create:${paymentIntent.paymentIntentId.value}"
        val stripeOptions = createStripeOptions(idempotencyKey)
        val paymentIntentCreateParams = createPaymentIntentParams(paymentIntent)
        return try {
            val stripePaymentIntent =
                stripeClient.v1().paymentIntents().create(paymentIntentCreateParams, stripeOptions)
            paymentIntent.markAsCreatedWithPspReferenceAndClientSecret(
                pspReference = stripePaymentIntent.id,
                clientSecret = stripePaymentIntent.clientSecret
            )
        } catch (e: Exception) {
            logger.error("calcretepapemyentintet api failed,", e)
            throw handleException("creation", e)
        }
    }

    private fun callConfirmPaymentIntentApi(paymentIntent: PaymentIntent, token: PaymentMethod?): PaymentIntent {
        val stripeConfirmIdempotencyKey = "confirm:${paymentIntent.paymentIntentId.value}"
        val stripeOptions = createStripeOptions(stripeConfirmIdempotencyKey)
        val paymentIntentConfirmParams = confirmPaymentIntentParams(token)
        return try {
            val confirmedStripePaymentIntent = stripeClient.v1().paymentIntents()
                .confirm(paymentIntent.pspReferenceOrThrow(), paymentIntentConfirmParams, stripeOptions)
            updatePaymentIntentStatus(paymentIntent, confirmedStripePaymentIntent)
        } catch (e: CardException) {
            // Stripe reports a card decline as an exception; for us a decline is a result
            paymentIntent.markDeclined()
        } catch (e: Exception) {
            throw handleException("confirmation", e)
        }
    }

    override fun retrieveClientSecret(pspReference: String): CompletableFuture<String>? {
        return submit(createPaymentIntentExecutor) {
            try {
                val retrieved = stripeClient.v1().paymentIntents().retrieve(pspReference)
                logger.debug(
                    "Retrieved clientSecret from Stripe: pspReference={}, status={}",
                    pspReference,
                    retrieved.status
                )
                retrieved.clientSecret
            } catch (e: Exception) {
                throw handleException("retrieval", e)
            }
        }
    }

    /**
     * Hands the PSP call to its thread pool. A full pool means the call was never sent:
     * not done, try again later.
     */
    private fun <T> submit(executor: ThreadPoolTaskExecutor, task: () -> T): CompletableFuture<T> {
        try {
            return CompletableFuture.supplyAsync({ task() }, executor)
        } catch (e: RejectedExecutionException) {
            throw PspTransientException("PSP call not sent: thread pool is full", e)
        }
    }

    private fun handleException(action: String, e: Exception): Exception {
        return when (e) {
            is StripeException -> {
                if (isRetryableError(e)) {
                    PspTransientException("Stripe $action transient failure: ${e.userMessage ?: e.message}", e)
                } else {
                    // CardException, InvalidRequestException etc. are permanent from our perspective
                    PspPermanentException("Stripe $action permanent failure: ${e.userMessage ?: e.message}", e)
                }
            }
            is SocketTimeoutException -> PspTransientException("Timeout during Stripe $action", e)
            is ApiConnectionException -> PspTransientException("Connection failure during Stripe $action", e)
            else -> {
                logger.error("Unexpected exception during Stripe $action", e)
                PspUnknownException("Unexpected error during Stripe $action", e)
            }
        }
    }

    private fun createStripeOptions(idempotencyKey: String): RequestOptions {
        return RequestOptions.builder()
            .setIdempotencyKey(idempotencyKey)
            .setMaxNetworkRetries(2)
            .build()
    }

    private fun createPaymentIntentParams(paymentIntent: PaymentIntent): PaymentIntentCreateParams {
        val params = PaymentIntentCreateParams.builder()
            .setAmount(paymentIntent.totalAmount.quantity)
            .setCurrency(paymentIntent.totalAmount.currency.currencyCode.lowercase())
            .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
            // We confirm server-side without a return_url, so only payment methods that never redirect
            // the customer (e.g. cards) are allowed; otherwise Stripe rejects the confirm.
            .setAutomaticPaymentMethods(
                PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                    .setEnabled(true)
                    .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                    .build()
            )
            .putMetadata("payment_intent_id", paymentIntent.paymentIntentId.value.toString())
            .putMetadata("order_id", paymentIntent.orderId.value)
            .putMetadata("buyer_id", paymentIntent.buyerId.value)
            .build()
        return params
    }

    private fun confirmPaymentIntentParams(token: PaymentMethod?): PaymentIntentConfirmParams {
        // the payment method comes back expanded, for the card's brand and last 4
        val paramsBuilder = PaymentIntentConfirmParams.builder().addExpand("payment_method")

        token?.let { paymentMethod ->
            val paymentMethodId = when (paymentMethod) {
                is PaymentMethod.CardToken -> paymentMethod.token // assume pm_...
                else -> {
                    throw PspInvalidPaymentException("Invalid Payment Method")
                }
            }
            paramsBuilder.setPaymentMethod(paymentMethodId)
            logger.debug("Using provided payment method: {}", paymentMethodId)
        } ?: run {
            logger.debug("No payment method provided - using payment method already attached to PaymentIntent")
        }
        return paramsBuilder.build()
    }

    private fun isRetryableError(e: StripeException): Boolean {
        // HTTP 429 and 5xx are always retryable
        val statusCode = e.statusCode
        if (statusCode == 429 || statusCode in 500..599) return true

        // Specific Stripe error codes that represent transient issues
        return when (e.code) {
            "api_error",
            "api_connection_error",
            "rate_limit_error",
            "idempotency_error",
            "service_unavailable",
            "network_error",
            "gateway_error" -> true
            else -> {
                // Heuristic for timeout messages if not caught by specific types
                e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("connection refused", ignoreCase = true) == true
            }
        }
    }

    private fun updatePaymentIntentStatus(paymentIntent: PaymentIntent, confirmed: com.stripe.model.PaymentIntent): PaymentIntent =
        when (confirmed.status?.uppercase()) {
            "REQUIRES_CAPTURE", "SUCCEEDED" -> paymentIntent.markAuthorized(cardSummaryOf(confirmed))
            "CANCELED", "CANCELLED" -> paymentIntent.markCancelled()
            "PROCESSING", "REQUIRES_ACTION" -> paymentIntent // not decided yet: stays PENDING_AUTH
            "REQUIRES_CONFIRMATION", "REQUIRES_PAYMENT_METHOD" -> paymentIntent.markDeclined()
            else -> paymentIntent.markDeclined()
        }

    // brand + last 4 of the card Stripe charged (payment_method expanded on confirm); null if Stripe did not include it
    private fun cardSummaryOf(confirmed: com.stripe.model.PaymentIntent): CardSummary? {
        val card = confirmed.paymentMethodObject?.card ?: return null
        return PspCardSummary.of(card.brand, card.last4)
    }
}
