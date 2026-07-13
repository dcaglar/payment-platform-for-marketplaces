package com.dogancaglar.paymentservice.infra.adapter.outbound.psp.stripe

import com.dogancaglar.paymentservice.infra.adapter.outbound.psp.simulator.AuthorizationNetworkSimulator
import com.dogancaglar.paymentservice.infra.adapter.outbound.psp.simulator.AuthorizationPspResponse
import com.dogancaglar.paymentservice.infra.adapter.outbound.psp.simulator.AuthorizationSimulationProperties
import com.dogancaglar.paymentservice.domain.exception.PspInvalidPaymentException
import com.dogancaglar.paymentservice.domain.exception.PspPermanentException
import com.dogancaglar.paymentservice.domain.exception.PspTransientException
import com.dogancaglar.paymentservice.domain.exception.PspUnknownException
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentMethod
import com.dogancaglar.paymentservice.ports.outbound.PspAuthorizationGatewayPort
import com.stripe.StripeClient
import com.stripe.exception.ApiConnectionException
import com.stripe.exception.StripeException
import com.stripe.net.RequestOptions
import com.stripe.param.PaymentIntentConfirmParams
import com.stripe.param.PaymentIntentCreateParams
import io.opentelemetry.api.OpenTelemetry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.net.SocketTimeoutException
import java.util.concurrent.*
import kotlin.random.Random

@Component
@ConditionalOnProperty(name = ["psp.gateway.type"], havingValue = "STRIPE", matchIfMissing = true)
@Profile("Test")
class StripePspAuthorizationGatewayAdapter(
    private val stripeClient: StripeClient,
    private val simulator: AuthorizationNetworkSimulator,
    private val config: AuthorizationSimulationProperties,
    @param:Qualifier("createPaymentIntentExecutor") private val createPaymentIntentExecutor: ThreadPoolTaskExecutor,
    @param:Qualifier("authorizePaymentIntentExecutor") private val authorizePaymentIntentExecutor: ThreadPoolTaskExecutor,
    openTelemetry: OpenTelemetry
) : PspAuthorizationGatewayPort {

    private val meter = openTelemetry.meterBuilder("payment-service.psp.stripe").build()

    private val pspQueueDelay = meter.histogramBuilder("psp_queue_delay")
        .setUnit("s")
        .build()

    private val pspExecDuration = meter.histogramBuilder("psp_exec_duration")
        .setUnit("s")
        .build()

    private val logger = LoggerFactory.getLogger(javaClass)

    private val active: AuthorizationSimulationProperties.ScenarioConfig
        get() = config.scenarios[config.scenario]
            ?: throw IllegalStateException("No scenario config for ${config.scenario}")

    override fun createPaymentIntent(paymentIntent: PaymentIntent): CompletableFuture<PaymentIntent> {
        //do not execute this in request thread, but hand the task to be executed in pspAuthExecutor
        /*Returns a new CompletableFuture that is asynchronously completed by a task( callCreatePaymentIntentApi(paymentIntent)) running in the given
         executor(pspAuthExecutor) with the value obtained by calling the given Supplier.
        */
        val futurePaymentIntent = CompletableFuture.supplyAsync({
            //actual task to be execited
            callCreatePaymentIntentApi(paymentIntent)
        }, createPaymentIntentExecutor)
        return futurePaymentIntent
    }

    override fun authorizePaymentIntent(paymentIntent: PaymentIntent, token: PaymentMethod?): CompletableFuture<PaymentIntent> {
        val futureAuthorizedPaymentIntent = CompletableFuture.supplyAsync({
            callConfirmPaymentIntentApi(paymentIntent, token)
        }, authorizePaymentIntentExecutor)

        return futureAuthorizedPaymentIntent
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
            logger.error("calcretepapemyentintet api failed,",e)
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
            updatePaymentIntentStatus(paymentIntent, confirmedStripePaymentIntent.status)
        } catch (e: Exception) {
            throw handleException("confirmation", e)
        }
    }

    override fun retrieveClientSecret(pspReference: String): CompletableFuture<String>? {
        return CompletableFuture.supplyAsync({
            try {
                val retrieved = stripeClient.v1().paymentIntents().retrieve(pspReference)
                logger.debug(
                    "Retrieved clientSecret from Stripe: pspReference={}, status={}",
                    pspReference, retrieved.status
                )
                retrieved.clientSecret
            } catch (e: Exception) {
                throw handleException("retrieval", e)
            }
        }, createPaymentIntentExecutor)
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
            .putMetadata("payment_intent_id", paymentIntent.paymentIntentId.value.toString())
            .putMetadata("order_id", paymentIntent.orderId.value)
            .putMetadata("buyer_id", paymentIntent.buyerId.value)
            .build()
        return params
    }

    private fun confirmPaymentIntentParams(token: PaymentMethod?): PaymentIntentConfirmParams {
        val paramsBuilder = PaymentIntentConfirmParams.builder()

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

    private fun updatePaymentIntentStatus(paymentIntent: PaymentIntent, stripeStatus: String?): PaymentIntent =
        when (stripeStatus?.uppercase()) {
            "REQUIRES_CAPTURE", "SUCCEEDED" -> paymentIntent.markAuthorized()
            "CANCELED", "CANCELLED" -> paymentIntent.markCancelled()
            "PROCESSING", "REQUIRES_ACTION" -> paymentIntent.markAuthorizedPending() // 3DS/SCA needs action -> Pending
            "REQUIRES_CONFIRMATION", "REQUIRES_PAYMENT_METHOD" -> paymentIntent.markDeclined()
            else -> paymentIntent.markDeclined()
        }

    private fun getAuthorizationResponse(): AuthorizationPspResponse {
        val roll = Random.nextInt(100)
        val result = when {
            roll < active.response.successful -> "AUTHORIZED"
            roll < active.response.successful + active.response.retryable -> "TRANSIENT_NETWORK_ERROR"
            roll < active.response.successful + active.response.retryable + active.response.nonRetryable -> "DECLINED"
            else -> "PENDING_AUTH"
        }
        return AuthorizationPspResponse(result)
    }
}