package com.dogancaglar.paymentservice.infra.adapter.outbound.psp.http

import com.dogancaglar.paymentservice.domain.exception.PspUnknownException
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.payment.CardBrand
import com.dogancaglar.paymentservice.domain.model.payment.CardSummary
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus
import com.dogancaglar.paymentservice.domain.model.payment.PaymentMethod
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.OrderId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.LocalDateTime
import java.util.concurrent.ExecutionException

/**
 * A PSP slower than our read timeout must always come back as "outcome unknown" (PspUnknownException),
 * so the payment stays PENDING_AUTH (the PSP may have authorized it). Our read timeout fires two ways that race
 * (the JDK's timeout and Spring's cancel); each must be translated, so the call is repeated many times.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpPspAuthorizationGatewayAdapterIntegrationTest {

    private val psp = WireMockServer(WireMockConfiguration.options().dynamicPort().containerThreads(50))
    private val pool = ThreadPoolTaskExecutor()
    private lateinit var adapter: HttpPspAuthorizationGatewayAdapter

    @BeforeAll
    fun start() {
        psp.start()
        // the PSP answers after 2 s; we wait 200 ms
        psp.stubFor(
            post(urlPathEqualTo("/v1/intents/psp_slow/authorize"))
                .willReturn(
                    aResponse().withStatus(200).withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"id":"psp_slow","status":"AUTHORIZED"}""")
                )
        )
        pool.corePoolSize = 4
        pool.initialize()
        adapter = HttpPspAuthorizationGatewayAdapter(
            baseUrl = psp.baseUrl(),
            connectTimeoutMs = 1000,
            readTimeoutMs = 200,
            objectMapper = ObjectMapper(),
            createPaymentIntentExecutor = pool,
            authorizePaymentIntentExecutor = pool
        )
    }

    @AfterAll
    fun stop() {
        pool.shutdown()
        psp.stop()
    }

    @Test
    fun `a PSP slower than our read timeout is always outcome unknown`() {
        val outcomes = mutableListOf<String>()
        for (attempt in 1..25) {
            try {
                adapter.authorizePaymentIntent(
                    pendingIntent(attempt),
                    PaymentMethod.CardToken("pm_card_visa")
                ).get()
                outcomes.add("answered")
            } catch (e: ExecutionException) {
                outcomes.add(e.cause!!::class.simpleName!!)
            }
        }

        for (outcome in outcomes) {
            assertThat(outcome).isEqualTo(PspUnknownException::class.simpleName)
        }
    }

    @Test
    fun `an authorization keeps the card brand and last 4 the PSP reports, and none when it reports none`() {
        psp.stubFor(
            post(urlPathEqualTo("/v1/intents/psp_visa/authorize"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""{"id":"psp_visa","status":"AUTHORIZED","card":{"brand":"visa","last4":"4242"}}""")
                )
        )
        psp.stubFor(
            post(urlPathEqualTo("/v1/intents/psp_mastercard/authorize"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"psp_mastercard","status":"AUTHORIZED",""" +
                                """"card":{"brand":"mastercard","last4":"4444"}}"""
                        )
                )
        )
        psp.stubFor(
            post(urlPathEqualTo("/v1/intents/psp_no_card/authorize"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""{"id":"psp_no_card","status":"AUTHORIZED"}""")
                )
        )
        val fastAdapter = HttpPspAuthorizationGatewayAdapter(
            baseUrl = psp.baseUrl(),
            connectTimeoutMs = 1000,
            readTimeoutMs = 2000,
            objectMapper = ObjectMapper(),
            createPaymentIntentExecutor = pool,
            authorizePaymentIntentExecutor = pool
        )
        val now = LocalDateTime.now()
        val visaIntent = PaymentIntent.rehydrate(
            paymentIntentId = PaymentIntentId(
                101
            ),
            pspReference = "psp_visa", buyerId = BuyerId("BUYER-1"), orderId = OrderId("ORDER-101"),
            totalAmount = Amount.of(
                5000,
                Currency("EUR")
            ),
            merchantAccount = "MARKETPLACE-5", processingModel = ProcessingModel.DIRECT_MERCHANT,
            splitsDelegate = lazyOf(
                emptyList()
            ),
            status = PaymentIntentStatus.PENDING_AUTH, createdAt = now, updatedAt = now
        )
        val mastercardIntent = PaymentIntent.rehydrate(
            paymentIntentId = PaymentIntentId(
                102
            ),
            pspReference = "psp_mastercard", buyerId = BuyerId("BUYER-1"), orderId = OrderId("ORDER-102"),
            totalAmount = Amount.of(
                5000,
                Currency("EUR")
            ),
            merchantAccount = "MARKETPLACE-5", processingModel = ProcessingModel.DIRECT_MERCHANT,
            splitsDelegate = lazyOf(
                emptyList()
            ),
            status = PaymentIntentStatus.PENDING_AUTH, createdAt = now, updatedAt = now
        )
        val noCardIntent = PaymentIntent.rehydrate(
            paymentIntentId = PaymentIntentId(
                103
            ),
            pspReference = "psp_no_card", buyerId = BuyerId("BUYER-1"), orderId = OrderId("ORDER-103"),
            totalAmount = Amount.of(
                5000,
                Currency("EUR")
            ),
            merchantAccount = "MARKETPLACE-5", processingModel = ProcessingModel.DIRECT_MERCHANT,
            splitsDelegate = lazyOf(
                emptyList()
            ),
            status = PaymentIntentStatus.PENDING_AUTH, createdAt = now, updatedAt = now
        )

        val visa = fastAdapter.authorizePaymentIntent(visaIntent, PaymentMethod.CardToken("pm_card_visa")).get()
        val mastercard = fastAdapter.authorizePaymentIntent(
            mastercardIntent,
            PaymentMethod.CardToken("pm_card_mastercard")
        ).get()
        val noCard = fastAdapter.authorizePaymentIntent(
            noCardIntent,
            PaymentMethod.CardToken("pm_card_visa")
        ).get()

        assertThat(visa.status).isEqualTo(PaymentIntentStatus.AUTHORIZED)
        assertThat(visa.cardSummary).isEqualTo(CardSummary.of(CardBrand.VISA, "4242"))
        assertThat(mastercard.cardSummary).isEqualTo(CardSummary.of(CardBrand.MASTERCARD, "4444"))
        assertThat(noCard.status).isEqualTo(PaymentIntentStatus.AUTHORIZED)
        assertThat(noCard.cardSummary).isNull()
    }

    private fun pendingIntent(id: Int): PaymentIntent {
        val now = LocalDateTime.now()
        return PaymentIntent.rehydrate(
            paymentIntentId = PaymentIntentId(id.toLong()),
            pspReference = "psp_slow",
            buyerId = BuyerId("BUYER-1"),
            orderId = OrderId("ORDER-$id"),
            totalAmount = Amount.of(5000, Currency("EUR")),
            merchantAccount = "MARKETPLACE-5",
            processingModel = ProcessingModel.DIRECT_MERCHANT,
            splitsDelegate = lazyOf(emptyList()),
            status = PaymentIntentStatus.PENDING_AUTH,
            createdAt = now,
            updatedAt = now
        )
    }
}
