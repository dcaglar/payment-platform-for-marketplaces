package com.dogancaglar.paymentservice.infra.adapter.outbound.psp.http

import com.dogancaglar.paymentservice.domain.exception.PspPermanentException
import com.dogancaglar.paymentservice.domain.exception.PspTransientException
import com.dogancaglar.paymentservice.domain.exception.PspUnknownException
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentMethod
import com.dogancaglar.paymentservice.ports.outbound.PspAuthorizationGatewayPort
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

/**
 * Talks to a PSP over HTTP. The PSP API (played by WireMock in tests):
 *   POST /v1/intents                  create    -> 200 {"id", "clientSecret", "status": "CREATED"}
 *   POST /v1/intents/{id}/authorize   authorize -> 200 {"id", "status": "AUTHORIZED" | "DECLINED" | "PENDING"}, 402 = declined
 *   GET  /v1/intents/{id}             retrieve  -> 200 {"id", "clientSecret", "status"}
 * Every call carries an Idempotency-Key derived from our payment intent id, so a retry of the
 * same step never makes the PSP do it twice.
 *
 * The answer is classified by HTTP status and network error only (never by message text):
 *   2xx with a known body              -> the result (a decline is a result, not an error)
 *   402 on authorize                   -> declined
 *   429, 503                           -> not done, try again           (PspTransientException)
 *   other 4xx                          -> the PSP refused our request   (PspPermanentException)
 *   other 5xx                          -> don't know if it was done     (PspUnknownException)
 *   connection refused, connect timeout, our pool full -> never sent, try again (PspTransientException)
 *   read timeout, reset, empty or garbled answer, unreadable 2xx       -> don't know (PspUnknownException)
 */
@Component
@ConditionalOnProperty(name = ["psp.gateway.type"], havingValue = "HTTP")
class HttpPspAuthorizationGatewayAdapter(
    @Value("\${psp.http.base-url}") baseUrl: String,
    @Value("\${psp.http.connect-timeout-ms:1000}") connectTimeoutMs: Long,
    @Value("\${psp.http.read-timeout-ms:10000}") readTimeoutMs: Long,
    @param:Qualifier("myObjectMapper") private val objectMapper: ObjectMapper,
    @param:Qualifier("createPaymentIntentExecutor") private val createPaymentIntentExecutor: ThreadPoolTaskExecutor,
    @param:Qualifier("authorizePaymentIntentExecutor") private val authorizePaymentIntentExecutor: ThreadPoolTaskExecutor
) : PspAuthorizationGatewayPort {

    private val restClient: RestClient

    init {
        val httpClient = HttpClient.newBuilder()
            // HTTP/1.1: every PSP API supports it; the JDK default (HTTP/2 upgrade) gets reset by some servers
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs))
        restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build()
    }

    override fun createPaymentIntent(paymentIntent: PaymentIntent): CompletableFuture<PaymentIntent> {
        return submit(createPaymentIntentExecutor) {
            val request = CreateIntentRequest(
                reference = paymentIntent.paymentIntentId.value.toString(),
                orderId = paymentIntent.orderId.value,
                merchantAccount = paymentIntent.merchantAccount,
                amount = paymentIntent.totalAmount.quantity,
                currency = paymentIntent.totalAmount.currency.currencyCode
            )
            val answer = send("create") {
                restClient.post()
                    .uri("/v1/intents")
                    .header("Idempotency-Key", "create-${paymentIntent.paymentIntentId.value}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
            }
            if (!answer.isSuccess()) {
                throw failure("create", answer.status)
            }
            val intent = readIntent("create", answer)
            if (intent.status != "CREATED" || intent.id.isNullOrBlank() || intent.clientSecret.isNullOrBlank()) {
                throw PspUnknownException("PSP create answered ${answer.status} with status=${intent.status}", null)
            }
            paymentIntent.markAsCreatedWithPspReferenceAndClientSecret(
                pspReference = intent.id,
                clientSecret = intent.clientSecret
            )
        }
    }

    override fun authorizePaymentIntent(paymentIntent: PaymentIntent, token: PaymentMethod?): CompletableFuture<PaymentIntent> {
        return submit(authorizePaymentIntentExecutor) {
            var paymentMethodToken: String? = null
            if (token is PaymentMethod.CardToken) {
                paymentMethodToken = token.token
            }
            val answer = send("authorize") {
                restClient.post()
                    .uri("/v1/intents/{id}/authorize", paymentIntent.pspReferenceOrThrow())
                    .header("Idempotency-Key", "authorize-${paymentIntent.paymentIntentId.value}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(AuthorizeRequest(paymentMethod = paymentMethodToken))
            }
            if (answer.status == 402) {
                // a decline is a result, not an error
                paymentIntent.markDeclined()
            } else if (!answer.isSuccess()) {
                throw failure("authorize", answer.status)
            } else {
                val intent = readIntent("authorize", answer)
                when (intent.status) {
                    "AUTHORIZED" -> paymentIntent.markAuthorized()
                    "DECLINED" -> paymentIntent.markDeclined()
                    "PENDING" -> paymentIntent // not decided yet: stays PENDING_AUTH
                    else -> throw PspUnknownException("PSP authorize answered unknown status=${intent.status}", null)
                }
            }
        }
    }

    override fun retrieveClientSecret(pspReference: String): CompletableFuture<String>? {
        return submit(createPaymentIntentExecutor) {
            val answer = send("retrieve") {
                restClient.get().uri("/v1/intents/{id}", pspReference)
            }
            if (!answer.isSuccess()) {
                throw failure("retrieve", answer.status)
            }
            val intent = readIntent("retrieve", answer)
            if (intent.clientSecret.isNullOrBlank()) {
                throw PspUnknownException("PSP retrieve answered without a client secret", null)
            }
            intent.clientSecret
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Hands the PSP call to its thread pool. A full pool means the call was never sent: try again later. */
    private fun <T> submit(executor: ThreadPoolTaskExecutor, task: () -> T): CompletableFuture<T> {
        try {
            return CompletableFuture.supplyAsync({ task() }, executor)
        } catch (e: RejectedExecutionException) {
            throw PspTransientException("PSP call not sent: thread pool is full", e)
        }
    }

    /** Sends the request and returns status + body, whatever the status. Network errors are classified here. */
    private fun send(action: String, request: () -> RestClient.RequestHeadersSpec<*>): PspAnswer {
        try {
            val answer = request().exchange { _, response ->
                PspAnswer(response.statusCode.value(), response.body.readAllBytes())
            }
            if (answer == null) {
                throw PspUnknownException("PSP $action gave no answer", null)
            }
            return answer
        } catch (e: ResourceAccessException) {
            val cause = e.cause
            if (cause is ConnectException || cause is HttpConnectTimeoutException) {
                throw PspTransientException("PSP $action not sent: ${cause::class.simpleName}", e)
            }
            // read timeout, connection reset, empty or garbled answer: the PSP may have done it
            throw PspUnknownException("PSP $action sent but no usable answer: ${cause?.let { it::class.simpleName }}", e)
        }
    }

    private fun failure(action: String, status: Int): RuntimeException {
        if (status == 429 || status == 503) {
            return PspTransientException("PSP $action not done, answered $status", null)
        }
        if (status in 400..499) {
            return PspPermanentException("PSP refused our $action request, answered $status", null)
        }
        return PspUnknownException("PSP $action answered $status, outcome unknown", null)
    }

    private fun readIntent(action: String, answer: PspAnswer): IntentResponse {
        try {
            return objectMapper.readValue(answer.body, IntentResponse::class.java)
        } catch (e: Exception) {
            throw PspUnknownException("PSP $action answered ${answer.status} with an unreadable body", e)
        }
    }

    private class PspAnswer(val status: Int, val body: ByteArray) {
        fun isSuccess(): Boolean = status in 200..299
    }

    data class CreateIntentRequest(
        val reference: String,
        val orderId: String,
        val merchantAccount: String,
        val amount: Long,
        val currency: String
    )

    data class AuthorizeRequest(val paymentMethod: String?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IntentResponse(
        val id: String? = null,
        val clientSecret: String? = null,
        val status: String? = null
    )
}
