package com.dogancaglar.paymentservice.infra.adapter.outbound.psp.stripe

import com.dogancaglar.paymentservice.domain.exception.PaymentPlatformException
import com.dogancaglar.paymentservice.domain.exception.PspInvalidPaymentException
import com.dogancaglar.paymentservice.domain.exception.PspOperation
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
import com.stripe.exception.ApiException
import com.stripe.exception.AuthenticationException
import com.stripe.exception.CardException
import com.stripe.exception.IdempotencyException
import com.stripe.exception.InvalidRequestException
import com.stripe.exception.RateLimitException
import com.stripe.exception.StripeException
import com.stripe.net.RequestOptions
import com.stripe.param.PaymentIntentConfirmParams
import com.stripe.param.PaymentIntentCreateParams
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
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
        return submit(createPaymentIntentExecutor, PspOperation.CREATE_INTENT, paymentIntent.paymentIntentId.value) {
            callCreatePaymentIntentApi(paymentIntent)
        }
    }

    override fun authorizePaymentIntent(paymentIntent: PaymentIntent, token: PaymentMethod?): CompletableFuture<PaymentIntent> {
        return submit(authorizePaymentIntentExecutor, PspOperation.AUTHORIZE, paymentIntent.paymentIntentId.value) {
            callConfirmPaymentIntentApi(paymentIntent, token)
        }
    }

    private fun callCreatePaymentIntentApi(paymentIntent: PaymentIntent): PaymentIntent {
        val idempotencyKey = "create:${paymentIntent.paymentIntentId.value}"
        val stripeOptions = createStripeOptions(idempotencyKey)
        val paymentIntentCreateParams = createPaymentIntentParams(paymentIntent)
        val stripePaymentIntent = try {
            stripeClient.v1().paymentIntents().create(paymentIntentCreateParams, stripeOptions)
        } catch (e: StripeException) {
            throw translate(PspOperation.CREATE_INTENT, paymentIntent.paymentIntentId.value, e)
        }
        return paymentIntent.markAsCreatedWithPspReferenceAndClientSecret(
            pspReference = stripePaymentIntent.id,
            clientSecret = stripePaymentIntent.clientSecret
        )
    }

    private fun callConfirmPaymentIntentApi(paymentIntent: PaymentIntent, token: PaymentMethod?): PaymentIntent {
        val stripeConfirmIdempotencyKey = "confirm:${paymentIntent.paymentIntentId.value}"
        val stripeOptions = createStripeOptions(stripeConfirmIdempotencyKey)
        val paymentIntentConfirmParams = confirmPaymentIntentParams(token)
        val pspReference = paymentIntent.pspReferenceOrThrow()
        val confirmedStripePaymentIntent = try {
            stripeClient.v1().paymentIntents().confirm(pspReference, paymentIntentConfirmParams, stripeOptions)
        } catch (@Suppress("SwallowedException") e: CardException) {
            // Stripe reports a declined (or blocked) payment as an exception; for us a decline is a result
            return paymentIntent.markDeclined()
        } catch (e: StripeException) {
            throw translate(PspOperation.AUTHORIZE, paymentIntent.paymentIntentId.value, e)
        }
        return updatePaymentIntentStatus(paymentIntent, confirmedStripePaymentIntent)
    }

    override fun retrieveClientSecret(paymentIntent: PaymentIntent): CompletableFuture<String>? {
        val op = PspOperation.RETRIEVE_CLIENT_SECRET
        val id = paymentIntent.paymentIntentId.value
        val pspReference = paymentIntent.pspReferenceOrThrow()
        return submit(createPaymentIntentExecutor, op, id) {
            val retrieved = try {
                stripeClient.v1().paymentIntents().retrieve(pspReference)
            } catch (e: StripeException) {
                throw translate(op, id, e)
            }
            logger.debug(
                "Retrieved clientSecret from Stripe: pspReference={}, status={}",
                pspReference,
                retrieved.status
            )
            retrieved.clientSecret
        }
    }

    /**
     * Hands the PSP call to its thread pool. A full pool means the call was never sent:
     * not done, try again later.
     */
    private fun <T> submit(
        executor: ThreadPoolTaskExecutor,
        op: PspOperation,
        id: Long,
        task: () -> T
    ): CompletableFuture<T> {
        try {
            return CompletableFuture.supplyAsync({ task() }, executor)
        } catch (e: RejectedExecutionException) {
            throw PspTransientException(op, id, "not sent: thread pool is full", e)
        }
    }

    /**
     * Stripe's exception -> our ExternalPspException, following Stripe's error-handling guidance
     * (https://docs.stripe.com/error-handling): connection and API (5xx) errors are "indeterminate", so Unknown.
     * Only the Stripe call is inside the try: our own errors are not translated. CardException is a decline,
     * handled where it can occur (confirm).
     */
    private fun translate(op: PspOperation, id: Long, e: StripeException): PaymentPlatformException {
        val stripe = "Stripe ${e.javaClass.simpleName} code=${e.code} requestId=${e.requestId}"
        return when (e) {
            is RateLimitException -> PspTransientException(
                op,
                id,
                "rate limited ($stripe)",
                e
            ) // before ApiException: a subclass
            is ApiConnectionException, is ApiException -> PspUnknownException(op, id, "outcome unknown ($stripe)", e)
            // our request or our API key is wrong (AuthenticationException also covers PermissionException)
            is InvalidRequestException, is IdempotencyException, is AuthenticationException ->
                PspPermanentException(op, id, "refused our request ($stripe)", e)
            else -> PspUnknownException(op, id, "failed ($stripe)", e)
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

    private fun updatePaymentIntentStatus(paymentIntent: PaymentIntent, confirmed: com.stripe.model.PaymentIntent): PaymentIntent =
        when (confirmed.status?.uppercase()) {
            "REQUIRES_CAPTURE", "SUCCEEDED" -> paymentIntent.markAuthorized(cardSummaryOf(confirmed))
            "CANCELED", "CANCELLED" -> paymentIntent.markCancelled()
            "PROCESSING", "REQUIRES_ACTION" -> paymentIntent // not decided yet: stays PENDING_AUTH
            "REQUIRES_CONFIRMATION", "REQUIRES_PAYMENT_METHOD" -> paymentIntent.markDeclined()
            else -> throw PspUnknownException(
                PspOperation.AUTHORIZE,
                paymentIntent.paymentIntentId.value,
                "Stripe answered unknown status=${confirmed.status}"
            )
        }

    // brand + last 4 of the card Stripe charged (payment_method expanded on confirm); null if Stripe did not include it
    private fun cardSummaryOf(confirmed: com.stripe.model.PaymentIntent): CardSummary? {
        val card = confirmed.paymentMethodObject?.card ?: return null
        return PspCardSummary.of(card.brand, card.last4)
    }
}
