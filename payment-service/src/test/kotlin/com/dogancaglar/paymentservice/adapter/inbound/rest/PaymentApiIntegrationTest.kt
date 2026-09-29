package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.common.id.PublicIdFactory
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import javax.sql.DataSource
import kotlin.random.Random

@Tag("integration")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PaymentApiIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:15"))
            .withDatabaseName("edge-db")
            .withUsername("test_user")
            .withPassword("test_password")

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)

        // WireMock plays the PSP; the mappings live in src/test/resources/wiremock/mappings
        @JvmStatic
        val psp: WireMockServer = WireMockServer(
            WireMockConfiguration.options().dynamicPort().usingFilesUnderClasspath("wiremock")
        )

        init {
            psp.start()
        }

        @AfterAll
        @JvmStatic
        fun stopPsp() {
            psp.stop()
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerDynamicProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.edge.url") { postgres.jdbcUrl + "&options=-c%20timezone=UTC" }
            registry.add("spring.datasource.edge.username") { postgres.username }
            registry.add("spring.datasource.edge.password") { postgres.password }
            // the real changelog (charts/payment-edge-cell/db), copied to the test classpath by the pom
            registry.add("spring.liquibase.enabled") { true }
            registry.add("spring.liquibase.change-log") { "classpath:db/changelog/changelog.edge.xml" }
            registry.add("spring.data.redis.url") { "redis://${redis.host}:${redis.getMappedPort(6379)}" }
            // the JWT decoder is only built on first use; the tests send a mocked JWT
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { "http://localhost:1/realms/test" }
            registry.add("psp.gateway.type") { "HTTP" }
            registry.add("psp.http.base-url") { psp.baseUrl() }
            // we give up on the PSP after 6 s (a hanging PSP answers after 9 s)
            registry.add("psp.http.read-timeout-ms") { 6000 }
            registry.add("otel.sdk.disabled") { true }
        }

        // not a WireMock tag: the test makes our PSP thread pool full for that one request
        const val POOL_FULL = "POOL-FULL"

        // CREATE PAYMENT rules:
        //   PSP created it          -> CREATED
        //   PSP refused it          -> FAILED (final)
        //   PSP unavailable/unknown -> stays CREATED_PENDING, answer 503
        //   PSP answers after our 3 s wait -> answer 202 (CREATED_PENDING), then the late answer decides:
        //       created -> CREATED; refused, error, reset or no answer before our 6 s timeout -> FAILED
        //   our PSP thread pool full -> answer 503, the PSP is not called
        //   the PSP is called once per request (no retries on our side)
        @JvmStatic
        fun createContract() = listOf(
            CreateCase("PSP creates the payment",     givenPsp = "OK",                expectedHttp = 201, expectedBody = "CREATED",         expectedStored = "CREATED", expectedPspCalls = 1),
            CreateCase("PSP refuses the payment",     givenPsp = "PSP-CREATE-REFUSE", expectedHttp = 422, expectedBody = "FAILED",          expectedStored = "FAILED", expectedPspCalls = 1),
            CreateCase("PSP temporarily unavailable", givenPsp = "PSP-CREATE-503",    expectedHttp = 503, expectedBody = "RETRY_LATER",     expectedStored = "CREATED_PENDING", expectedPspCalls = 1),
            CreateCase("PSP outcome unknown",         givenPsp = "PSP-CREATE-RESET",  expectedHttp = 503, expectedBody = "RETRY_LATER",     expectedStored = "CREATED_PENDING", expectedPspCalls = 1),
            CreateCase("PSP creates it after 5 s",    givenPsp = "PSP-CREATE-SLOW",   expectedHttp = 202, expectedBody = "CREATED_PENDING", expectedStored = "CREATED_PENDING", expectedPspCalls = 1, expectedStoredLater = "CREATED"),
            CreateCase("PSP refuses it after 5 s",    givenPsp = "PSP-CREATE-LATE-REFUSE", expectedHttp = 202, expectedBody = "CREATED_PENDING", expectedStored = "CREATED_PENDING", expectedPspCalls = 1, expectedStoredLater = "FAILED"),
            CreateCase("PSP unavailable after 4 s (503)", givenPsp = "PSP-CREATE-LATE-503", expectedHttp = 202, expectedBody = "CREATED_PENDING", expectedStored = "CREATED_PENDING", expectedPspCalls = 1, expectedStoredLater = "FAILED"),
            CreateCase("PSP error after 4 s (500)",   givenPsp = "PSP-CREATE-LATE-500", expectedHttp = 202, expectedBody = "CREATED_PENDING", expectedStored = "CREATED_PENDING", expectedPspCalls = 1, expectedStoredLater = "FAILED"),
            CreateCase("connection reset after 4 s",  givenPsp = "PSP-CREATE-LATE-RESET", expectedHttp = 202, expectedBody = "CREATED_PENDING", expectedStored = "CREATED_PENDING", expectedPspCalls = 1, expectedStoredLater = "FAILED"),
            CreateCase("PSP error (500)",             givenPsp = "PSP-CREATE-500",    expectedHttp = 503, expectedBody = "RETRY_LATER",     expectedStored = "CREATED_PENDING", expectedPspCalls = 1),
            CreateCase("PSP bad gateway (502)",       givenPsp = "PSP-CREATE-502",    expectedHttp = 503, expectedBody = "RETRY_LATER",     expectedStored = "CREATED_PENDING", expectedPspCalls = 1),
            CreateCase("PSP gateway timeout (504)",   givenPsp = "PSP-CREATE-504",    expectedHttp = 503, expectedBody = "RETRY_LATER",     expectedStored = "CREATED_PENDING", expectedPspCalls = 1),
            CreateCase("PSP rate limits us (429)",    givenPsp = "PSP-CREATE-429",    expectedHttp = 503, expectedBody = "RETRY_LATER",     expectedStored = "CREATED_PENDING", expectedPspCalls = 1),
            CreateCase("PSP answer unreadable",       givenPsp = "PSP-CREATE-NOT-JSON", expectedHttp = 503, expectedBody = "RETRY_LATER",   expectedStored = "CREATED_PENDING", expectedPspCalls = 1),
            CreateCase("PSP hangs past our timeout",  givenPsp = "PSP-CREATE-HANG",   expectedHttp = 202, expectedBody = "CREATED_PENDING", expectedStored = "CREATED_PENDING", expectedPspCalls = 1, expectedStoredLater = "FAILED"),
            CreateCase("our PSP thread pool is full", givenPsp = POOL_FULL,           expectedHttp = 503, expectedBody = "RETRY_LATER",     expectedStored = "CREATED_PENDING", expectedPspCalls = 0),
        )

        // AUTHORIZE rules:
        //   PSP authorized             -> AUTHORIZED
        //   PSP declined the card      -> DECLINED (final)
        //   PSP unavailable/unknown    -> back to CREATED, answer 503
        //   PSP refused our request    -> FAILED (final)
        //   PSP has not decided        -> PENDING_AUTH, answer 202
        //   PSP answers after our 3 s wait -> answer 202 (PENDING_AUTH), then the late answer decides:
        //       authorized -> AUTHORIZED; declined -> DECLINED; refused -> FAILED;
        //       unavailable, error, reset or no answer before our 6 s timeout -> back to CREATED
        //   our PSP thread pool full   -> answer 503, back to CREATED, the PSP is not called
        //   payment not in CREATED     -> the PSP is not called, the current state answers
        //   otherwise the PSP is called once per request (no retries on our side)
        @JvmStatic
        fun authorizeContract() = listOf(
            AuthorizeCase("card accepted",               givenPayment = "CREATED",         givenPsp = "PSP-AUTH-OK",          expectedHttp = 200, expectedBody = "AUTHORIZED",   expectedStored = "AUTHORIZED", expectedPspCalls = 1),
            AuthorizeCase("card declined",               givenPayment = "CREATED",         givenPsp = "PSP-AUTH-DECLINE-402", expectedHttp = 200, expectedBody = "DECLINED",     expectedStored = "DECLINED", expectedPspCalls = 1),
            AuthorizeCase("PSP temporarily unavailable", givenPayment = "CREATED",         givenPsp = "PSP-AUTH-503",         expectedHttp = 503, expectedBody = "RETRY_LATER",  expectedStored = "CREATED", expectedPspCalls = 1),
            AuthorizeCase("PSP outcome unknown",         givenPayment = "CREATED",         givenPsp = "PSP-AUTH-RESET",       expectedHttp = 503, expectedBody = "RETRY_LATER",  expectedStored = "CREATED", expectedPspCalls = 1),
            AuthorizeCase("PSP refuses our request",     givenPayment = "CREATED",         givenPsp = "PSP-AUTH-400",         expectedHttp = 422, expectedBody = "FAILED",       expectedStored = "FAILED", expectedPspCalls = 1),
            AuthorizeCase("PSP not decided yet",         givenPayment = "CREATED",         givenPsp = "PSP-AUTH-PENDING",     expectedHttp = 202, expectedBody = "PENDING_AUTH", expectedStored = "PENDING_AUTH", expectedPspCalls = 1),
            AuthorizeCase("PSP error (500)",             givenPayment = "CREATED",         givenPsp = "PSP-AUTH-500",         expectedHttp = 503, expectedBody = "RETRY_LATER",  expectedStored = "CREATED", expectedPspCalls = 1),
            AuthorizeCase("PSP bad gateway (502)",       givenPayment = "CREATED",         givenPsp = "PSP-AUTH-502",         expectedHttp = 503, expectedBody = "RETRY_LATER",  expectedStored = "CREATED", expectedPspCalls = 1),
            AuthorizeCase("PSP gateway timeout (504)",   givenPayment = "CREATED",         givenPsp = "PSP-AUTH-504",         expectedHttp = 503, expectedBody = "RETRY_LATER",  expectedStored = "CREATED", expectedPspCalls = 1),
            AuthorizeCase("PSP rate limits us (429)",    givenPayment = "CREATED",         givenPsp = "PSP-AUTH-429",         expectedHttp = 503, expectedBody = "RETRY_LATER",  expectedStored = "CREATED", expectedPspCalls = 1),
            AuthorizeCase("PSP answer unreadable",       givenPayment = "CREATED",         givenPsp = "PSP-AUTH-NOT-JSON",    expectedHttp = 503, expectedBody = "RETRY_LATER",  expectedStored = "CREATED", expectedPspCalls = 1),
            AuthorizeCase("PSP authorizes after 5 s",    givenPayment = "CREATED",         givenPsp = "PSP-AUTH-SLOW",        expectedHttp = 202, expectedBody = "PENDING_AUTH", expectedStored = "PENDING_AUTH", expectedPspCalls = 1, expectedStoredLater = "AUTHORIZED"),
            AuthorizeCase("PSP declines after 5 s",      givenPayment = "CREATED",         givenPsp = "PSP-AUTH-LATE-DECLINE", expectedHttp = 202, expectedBody = "PENDING_AUTH", expectedStored = "PENDING_AUTH", expectedPspCalls = 1, expectedStoredLater = "DECLINED"),
            AuthorizeCase("PSP refuses after 5 s (400)", givenPayment = "CREATED",         givenPsp = "PSP-AUTH-LATE-REFUSE", expectedHttp = 202, expectedBody = "PENDING_AUTH", expectedStored = "PENDING_AUTH", expectedPspCalls = 1, expectedStoredLater = "FAILED"),
            AuthorizeCase("PSP unavailable after 4 s (503)", givenPayment = "CREATED",     givenPsp = "PSP-AUTH-LATE-503",    expectedHttp = 202, expectedBody = "PENDING_AUTH", expectedStored = "PENDING_AUTH", expectedPspCalls = 1, expectedStoredLater = "CREATED"),
            AuthorizeCase("PSP error after 4 s (500)",   givenPayment = "CREATED",         givenPsp = "PSP-AUTH-LATE-500",    expectedHttp = 202, expectedBody = "PENDING_AUTH", expectedStored = "PENDING_AUTH", expectedPspCalls = 1, expectedStoredLater = "CREATED"),
            AuthorizeCase("connection reset after 4 s",  givenPayment = "CREATED",         givenPsp = "PSP-AUTH-LATE-RESET",  expectedHttp = 202, expectedBody = "PENDING_AUTH", expectedStored = "PENDING_AUTH", expectedPspCalls = 1, expectedStoredLater = "CREATED"),
            AuthorizeCase("PSP hangs past our timeout",  givenPayment = "CREATED",         givenPsp = "PSP-AUTH-HANG",        expectedHttp = 202, expectedBody = "PENDING_AUTH", expectedStored = "PENDING_AUTH", expectedPspCalls = 1, expectedStoredLater = "CREATED"),
            AuthorizeCase("our PSP thread pool is full", givenPayment = "CREATED",         givenPsp = POOL_FULL,              expectedHttp = 503, expectedBody = "RETRY_LATER",  expectedStored = "CREATED", expectedPspCalls = 0),
            AuthorizeCase("already being authorized",    givenPayment = "PENDING_AUTH",    givenPsp = null,                   expectedHttp = 202, expectedBody = "PENDING_AUTH", expectedStored = "PENDING_AUTH", expectedPspCalls = 0),
            AuthorizeCase("already declined (final)",    givenPayment = "DECLINED",        givenPsp = null,                   expectedHttp = 200, expectedBody = "DECLINED",     expectedStored = "DECLINED", expectedPspCalls = 0),
            AuthorizeCase("not created at the PSP yet",  givenPayment = "CREATED_PENDING", givenPsp = null,                   expectedHttp = 409, expectedBody = "IN_PROGRESS",  expectedStored = "CREATED_PENDING", expectedPspCalls = 0),
            AuthorizeCase("unknown payment",             givenPayment = null,              givenPsp = null,                   expectedHttp = 404, expectedBody = "NOT_FOUND",    expectedStored = null, expectedPspCalls = 0),
        )
    }

    // givenPsp: the tag in the orderId that tells WireMock how the PSP answers
    // expectedBody: the error `code` in our answer, or the payment `status` when it is not an error
    // expectedStored: the payment's status in the database afterwards
    // expectedPspCalls: how many requests reached the PSP (WireMock counts them)
    // expectedStoredLater: what the payment must become after the PSP's late answer or our timeout
    data class CreateCase(
        val situation: String, val givenPsp: String,
        val expectedHttp: Int, val expectedBody: String, val expectedStored: String, val expectedPspCalls: Int,
        val expectedStoredLater: String? = null
    ) {
        override fun toString() = situation
    }

    // givenPayment: the payment's status before the request (null = no such payment)
    // givenPsp: null = the PSP must not be called
    data class AuthorizeCase(
        val situation: String, val givenPayment: String?, val givenPsp: String?,
        val expectedHttp: Int, val expectedBody: String, val expectedStored: String?, val expectedPspCalls: Int,
        val expectedStoredLater: String? = null
    ) {
        override fun toString() = situation
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    @Qualifier("createPaymentIntentExecutor")
    private lateinit var createPool: ThreadPoolTaskExecutor

    @Autowired
    @Qualifier("authorizePaymentIntentExecutor")
    private lateinit var authorizePool: ThreadPoolTaskExecutor

    private val objectMapper = ObjectMapper()

    // the changelog creates only the partitioned outbox parent (payment-edge-workers creates partitions in production)
    @BeforeEach
    fun createOutboxPartition() {
        jdbc().execute("CREATE TABLE IF NOT EXISTS outbox_event_default PARTITION OF outbox_event DEFAULT")
    }

    // ============================================================================ create payment

    @ParameterizedTest(name = "create: {0}")
    @MethodSource("createContract")
    fun `create payment`(case: CreateCase) {
        val orderId = orderId(case.givenPsp)

        val startedAt = System.currentTimeMillis()
        val result: MvcResult
        if (case.givenPsp == POOL_FULL) {
            result = withFullPool(createPool) { create(newKey(), directSale(orderId)) }
        } else {
            result = create(newKey(), directSale(orderId))
        }
        val answeredInMs = System.currentTimeMillis() - startedAt

        assertThat(answeredInMs).isLessThan(4000)   // we wait at most 3 s for the PSP
        assertThat(result.response.status).isEqualTo(case.expectedHttp)
        assertThat(answer(result)).isEqualTo(case.expectedBody)
        assertThat(storedStatus(orderId)).isEqualTo(case.expectedStored)
        assertThat(pspCreateCalls(orderId)).isEqualTo(case.expectedPspCalls)
        if (case.expectedStoredLater != null) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted {
                assertThat(storedStatus(orderId)).isEqualTo(case.expectedStoredLater)
            }
        }
    }

    @Test
    fun `create - the same key and body again gets exactly the first answer, the PSP is not called again`() {
        val orderId = orderId("REPLAY")
        val key = newKey()
        val first = create(key, directSale(orderId))

        val replay = create(key, directSale(orderId))

        assertThat(replay.response.status).isEqualTo(201)
        assertThat(replay.response.getHeader("Idempotent-Replayed")).isEqualTo("true")
        assertThat(json(replay)["paymentIntentId"]).isEqualTo(json(first)["paymentIntentId"])
        assertThat(pspCreateCalls(orderId)).isEqualTo(1)
    }

    @Test
    fun `create - the same key with a different body gets 422 KEY_REUSED`() {
        val key = newKey()
        create(key, directSale(orderId("FIRST")))

        val result = create(key, directSale(orderId("SECOND")))

        assertThat(result.response.status).isEqualTo(422)
        assertThat(answer(result)).isEqualTo("KEY_REUSED")
    }

    @Test
    fun `create - an invalid request gets 400 INVALID_REQUEST and nothing is stored`() {
        val orderId = orderId("INVALID")
        // a direct sale must not have splits
        val body = directSale(orderId).replace(
            "\"totalAmount\"",
            "\"splits\": [ { \"type\": \"Commission\", \"amount\": { \"quantity\": 5000, \"currency\": \"EUR\" } } ], \"totalAmount\""
        )

        val result = create(newKey(), body)

        assertThat(result.response.status).isEqualTo(400)
        assertThat(answer(result)).isEqualTo("INVALID_REQUEST")
        assertThat(storedStatus(orderId)).isNull()
    }

    // ================================================================================= authorize

    @ParameterizedTest(name = "authorize: {0}")
    @MethodSource("authorizeContract")
    fun `authorize`(case: AuthorizeCase) {
        val orderId = orderId(case.givenPsp ?: "NO-PSP-CALL")
        val publicId: String
        if (case.givenPayment == null) {
            publicId = PublicIdFactory.publicPaymentIntentId(Random.nextLong(1, Long.MAX_VALUE))
        } else {
            publicId = createdPayment(orderId)
            setStoredStatus(orderId, case.givenPayment)
        }

        val startedAt = System.currentTimeMillis()
        val result: MvcResult
        if (case.givenPsp == POOL_FULL) {
            result = withFullPool(authorizePool) { authorize(publicId) }
        } else {
            result = authorize(publicId)
        }
        val answeredInMs = System.currentTimeMillis() - startedAt

        assertThat(answeredInMs).isLessThan(4000)   // we wait at most 3 s for the PSP
        assertThat(result.response.status).isEqualTo(case.expectedHttp)
        assertThat(answer(result)).isEqualTo(case.expectedBody)
        assertThat(storedStatus(orderId)).isEqualTo(case.expectedStored)
        assertThat(pspAuthorizeCalls(orderId)).isEqualTo(case.expectedPspCalls)
        if (case.expectedStoredLater != null) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted {
                assertThat(storedStatus(orderId)).isEqualTo(case.expectedStoredLater)
            }
        }
    }

    @Test
    fun `authorize - the PSP authorized but we could not save it gets 202 PENDING_AUTH, never a failure`() {
        val orderId = orderId("PSP-AUTH-OK")
        val publicId = createdPayment(orderId)
        // without an outbox partition the payment_authorized event cannot be saved
        jdbc().execute("ALTER TABLE outbox_event DETACH PARTITION outbox_event_default")
        try {
            val result = authorize(publicId)

            assertThat(result.response.status).isEqualTo(202)
            assertThat(answer(result)).isEqualTo("PENDING_AUTH")
            assertThat(storedStatus(orderId)).isEqualTo("PENDING_AUTH")
        } finally {
            jdbc().execute("ALTER TABLE outbox_event ATTACH PARTITION outbox_event_default DEFAULT")
        }
    }

    // ================================================================================== security

    @Test
    fun `a request without a token gets 401`() {
        val result = mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(directSale(orderId("NO-TOKEN")))
        ).andReturn()

        assertThat(result.response.status).isEqualTo(401)
    }

    @Test
    fun `a token without payment write gets 403`() {
        val result = mockMvc.perform(
            post("/api/v1/payments")
                .with(jwt().authorities(SimpleGrantedAuthority("something:else")))
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(directSale(orderId("NO-PERMISSION")))
        ).andReturn()

        assertThat(result.response.status).isEqualTo(403)
    }

    // =================================================================================== helpers

    private fun create(key: String, body: String): MvcResult {
        return mockMvc.perform(
            post("/api/v1/payments")
                .with(jwt().authorities(SimpleGrantedAuthority("payment:write")))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andReturn()
    }

    private fun authorize(publicId: String): MvcResult {
        return mockMvc.perform(
            post("/api/v1/payments/$publicId/authorize")
                .with(jwt().authorities(SimpleGrantedAuthority("payment:write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        ).andReturn()
    }

    // setup: a payment the PSP created (201); returns its public id
    private fun createdPayment(orderId: String): String {
        val result = create(newKey(), directSale(orderId))
        assertThat(result.response.status).isEqualTo(201)
        return json(result)["paymentIntentId"].asText()
    }

    private fun directSale(orderId: String): String = """
        {
          "orderId": "$orderId",
          "buyerId": "BUYER-1",
          "merchantAccount": "MARKETPLACE-1",
          "processingModel": "DIRECT_MERCHANT",
          "totalAmount": { "quantity": 5000, "currency": "EUR" }
        }
    """.trimIndent()

    // setup: the PSP thread pool is full (one busy thread, full queue) while [request] runs, then restored
    private fun withFullPool(pool: ThreadPoolTaskExecutor, request: () -> MvcResult): MvcResult {
        val executor = pool.threadPoolExecutor
        val coreBefore = executor.corePoolSize
        val maxBefore = executor.maximumPoolSize
        val release = CountDownLatch(1)
        executor.corePoolSize = 1
        executor.maximumPoolSize = 1
        try {
            // threads above the new size stay alive for a moment and take queued tasks,
            // so fill, let them settle, and fill again
            fillUntilRefused(executor, release)
            Thread.sleep(200)
            fillUntilRefused(executor, release)
            return request()
        } finally {
            release.countDown()
            executor.maximumPoolSize = maxBefore
            executor.corePoolSize = coreBefore
        }
    }

    private fun fillUntilRefused(executor: ThreadPoolExecutor, release: CountDownLatch) {
        var refused = false
        while (!refused) {
            try {
                executor.execute { release.await() }
            } catch (e: RejectedExecutionException) {
                refused = true
            }
        }
    }

    // a unique order id; the tag tells WireMock how the PSP answers
    private fun orderId(tag: String): String = "ORD-${UUID.randomUUID().toString().take(8)}-$tag"

    private fun json(result: MvcResult): JsonNode = objectMapper.readTree(result.response.contentAsString)

    // the error `code` for an error answer, the payment `status` otherwise
    private fun answer(result: MvcResult): String {
        val body = json(result)
        if (body.has("code")) {
            return body["code"].asText()
        }
        return body["status"].asText()
    }

    private fun jdbc() = JdbcTemplate(dataSource)

    private fun storedStatus(orderId: String): String? {
        val statuses = jdbc().queryForList("SELECT status FROM payment_intents WHERE order_id = ?", String::class.java, orderId)
        if (statuses.isEmpty()) {
            return null
        }
        return statuses[0]
    }

    // setup: put the payment in a state (a payment the PSP has not created yet has no PSP reference)
    private fun setStoredStatus(orderId: String, status: String) {
        if (status == "CREATED_PENDING") {
            jdbc().update("UPDATE payment_intents SET status = ?, psp_reference = NULL WHERE order_id = ?", status, orderId)
        } else {
            jdbc().update("UPDATE payment_intents SET status = ? WHERE order_id = ?", status, orderId)
        }
    }

    private fun pspCreateCalls(orderId: String): Int =
        psp.findAll(postRequestedFor(urlPathEqualTo("/v1/intents")).withRequestBody(containing(orderId))).size

    private fun pspAuthorizeCalls(orderId: String): Int =
        psp.findAll(postRequestedFor(urlPathEqualTo("/v1/intents/psp_$orderId/authorize"))).size

    // a UUIDv7, as the API requires for the Idempotency-Key
    private fun newKey(): String {
        val timestamp = System.currentTimeMillis() and 0xFFFFFFFFFFFFL
        val randA = Random.nextInt(0x1000)
        val randB = Random.nextLong() and 0x3FFFFFFFFFFFFFFFL
        val msb = (timestamp shl 16) or (0x7L shl 12) or randA.toLong()
        val lsb = (0x2L shl 62) or randB
        return UUID(msb, lsb).toString()
    }
}
