package com.dogancaglar.paymentservice.infra.adapter.outbound.serialization

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.BeforeEach

class JacksonSerializationAdapterTest {

    private lateinit var objectMapper: ObjectMapper
    private lateinit var adapter: JacksonSerializationAdapter

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper().apply {
            registerModule(JavaTimeModule())
            registerKotlinModule()
        }
        adapter = JacksonSerializationAdapter(objectMapper)
    }

    /*

    @Test
    fun `toJson serializes payment order`() {
        val order = createTestPaymentOrder()

        val json = adapter.toJson(order)

        assertNotNull(json)
        assertTrue(json.contains("\"paymentOrderId\":${order.paymentOrderId.value}"))
        assertTrue(json.contains("\"paymentId\":${order.paymentId.value}"))
        assertTrue(json.contains("\"retryCount\":${order.retryCount}"))
        assertTrue(json.contains("\"currency\":\"${order.amount.currency.currencyCode}\""))
    }

    @Test
    fun `toJson serializes maps and lists`() {
        val payload = mapOf(
            "metadata" to mapOf("source" to "mobile-app", "version" to "1.2.3"),
            "items" to listOf(
                mapOf("sku" to "A1", "qty" to 2),
                mapOf("sku" to "B2", "qty" to 1)
            )
        )

        val json = adapter.toJson(payload)

        assertNotNull(json)
        assertTrue(json.contains("\"metadata\""))
        assertTrue(json.contains("\"items\""))
        assertTrue(json.contains("\"sku\":\"A1\""))
    }

    @Test
    fun `toJson serializes LocalDateTime values`() {
        val timestamp = Utc.fromInstant(Instant.parse("2024-01-01T12:00:00Z"))

        val json = adapter.toJson(mapOf("createdAt" to timestamp))

        assertNotNull(json)
        val node = objectMapper.readTree(json)
        assertTrue(node.has("createdAt"))
    }

    @Test
    fun `toJson delegates to configured ObjectMapper`() {
        val mockMapper = mockk<ObjectMapper>(relaxed = true)
        val testAdapter = JacksonSerializationAdapter(mockMapper)
        val order = createTestPaymentOrder()
        val expected = """{"ok":true}"""

        every { mockMapper.writeValueAsString(order) } returns expected

        val result = testAdapter.toJson(order)

        assertEquals(expected, result)
        verify(exactly = 1) { mockMapper.writeValueAsString(order) }
    }

    @Test
    fun `toJson propagates ObjectMapper exceptions`() {
        val mockMapper = mockk<ObjectMapper>(relaxed = true)
        val testAdapter = JacksonSerializationAdapter(mockMapper)
        val order = createTestPaymentOrder()

        every { mockMapper.writeValueAsString(order) } throws RuntimeException("boom")

        assertThrows<RuntimeException> {
            testAdapter.toJson(order)
        }
    }

    private fun createTestPaymentOrder(
        id: Long = 123L,
        retryCount: Int = 0,
        status: PaymentOrderStatus = PaymentIntentStatus.CAPTURE_RECEIVED
    ): PaymentOrder =
        PaymentOrder.rehydrate(
            paymentOrderId = PaymentOrderId(id),
            paymentId = PaymentId(999L),
            sellerId = SellerId("111"),
            amount = Amount.of(10000L, Currency("USD")),
            status = status,
            retryCount = retryCount,
            createdAt = Utc.nowLocalDateTime(),
            updatedAt = Utc.nowLocalDateTime()
        )

     */
}
