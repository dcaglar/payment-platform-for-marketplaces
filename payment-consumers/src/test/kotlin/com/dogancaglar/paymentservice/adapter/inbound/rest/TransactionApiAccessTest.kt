package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.application.transaction.Transaction
import com.dogancaglar.paymentservice.config.SecurityConfig
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.OrderId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.ports.inbound.usecases.TransactionUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant

/**
 * Who gets through to the transaction API: decided by the token alone (its permissions and claims).
 * 403 = refused, 401 = no token; let through = 200 (or 404 when the transaction does not exist).
 * Each test says which transaction exists behind the controller.
 */
@WebMvcTest(TransactionController::class)
@Import(SecurityConfig::class)
// beans the controller's context needs; the tests never call them
@MockitoBean(types = [JwtDecoder::class])
class TransactionApiAccessTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var transactionUseCase: TransactionUseCase

    @Test
    fun `a merchant can list and read its own transactions, not staff's`() {
        val merchant = jwt().jwt {
            it.claim("merchant_id", "MARKETPLACE-5")
        }.authorities(SimpleGrantedAuthority("transaction:read"))
        // MARKETPLACE-5 has transaction 1002; 1001 does not exist
        val transaction1002 = Transaction(
            paymentId = PaymentId(1002),
            paymentIntentId = PaymentIntentId(1102),
            publicPaymentIntentId = "pi_1002",
            merchantAccount = "MARKETPLACE-5",
            buyerId = BuyerId("BUYER-1"),
            orderId = OrderId("ORDER-2"),
            pspReference = "psp_1002",
            processingModel = ProcessingModel.MARKETPLACE,
            totalAmount = Amount.of(3000, Currency("EUR")),
            splits = emptyList(),
            authorizedAt = Instant.parse("2026-10-02T10:00:02Z")
        )
        `when`(transactionUseCase.getTransaction(PaymentId(1002), "MARKETPLACE-5")).thenReturn(transaction1002)

        val ownList = mockMvc.get("/api/v1/transactions/merchants/me") { with(merchant) }.andReturn().response.status
        val ownExisting = mockMvc.get(
            "/api/v1/transactions/merchants/me/1002"
        ) { with(merchant) }.andReturn().response.status
        val ownMissing = mockMvc.get(
            "/api/v1/transactions/merchants/me/1001"
        ) { with(merchant) }.andReturn().response.status
        val staffList = mockMvc.get("/api/v1/transactions/merchants/MARKETPLACE-5") {
            with(merchant)
        }.andReturn().response.status
        val staffDetail = mockMvc.get("/api/v1/transactions/merchants/MARKETPLACE-5/1002") {
            with(merchant)
        }.andReturn().response.status

        assertEquals(HttpStatus.OK.value(), ownList)
        assertEquals(HttpStatus.OK.value(), ownExisting)
        assertEquals(HttpStatus.NOT_FOUND.value(), ownMissing) // let through, but there is no such transaction
        assertEquals(HttpStatus.FORBIDDEN.value(), staffList) // not staff, even naming itself
        assertEquals(HttpStatus.FORBIDDEN.value(), staffDetail)
    }

    @Test
    fun `support can list and read the transactions of any merchant it names, and has none of its own`() {
        val support = jwt().authorities(
            SimpleGrantedAuthority("transaction:read"),
            SimpleGrantedAuthority("merchant:all")
        )
        // MARKETPLACE-1 has transaction 2001; 1001 does not exist
        val transaction2001 = Transaction(
            paymentId = PaymentId(2001),
            paymentIntentId = PaymentIntentId(2101),
            publicPaymentIntentId = "pi_2001",
            merchantAccount = "MARKETPLACE-1",
            buyerId = BuyerId("BUYER-1"),
            orderId = OrderId("ORDER-9"),
            pspReference = "psp_2001",
            processingModel = ProcessingModel.MARKETPLACE,
            totalAmount = Amount.of(3000, Currency("EUR")),
            splits = emptyList(),
            authorizedAt = Instant.parse("2026-10-02T10:00:03Z")
        )
        `when`(transactionUseCase.getTransaction(PaymentId(2001), "MARKETPLACE-1")).thenReturn(transaction2001)

        val list = mockMvc.get(
            "/api/v1/transactions/merchants/MARKETPLACE-1"
        ) { with(support) }.andReturn().response.status
        val existing = mockMvc.get("/api/v1/transactions/merchants/MARKETPLACE-1/2001") {
            with(support)
        }.andReturn().response.status
        val missing = mockMvc.get("/api/v1/transactions/merchants/MARKETPLACE-1/1001") {
            with(support)
        }.andReturn().response.status
        val ownList = mockMvc.get("/api/v1/transactions/merchants/me") { with(support) }.andReturn().response.status

        assertEquals(HttpStatus.OK.value(), list)
        assertEquals(HttpStatus.OK.value(), existing)
        assertEquals(HttpStatus.NOT_FOUND.value(), missing) // let through, but there is no such transaction
        assertEquals(HttpStatus.FORBIDDEN.value(), ownList) // no merchant_id
    }

    @Test
    fun `a token with transaction read but neither merchant_id nor merchant all reads nothing`() {
        val neither = jwt().authorities(SimpleGrantedAuthority("transaction:read"))

        val ownList = mockMvc.get("/api/v1/transactions/merchants/me") { with(neither) }.andReturn().response.status
        val ownDetail = mockMvc.get(
            "/api/v1/transactions/merchants/me/1001"
        ) { with(neither) }.andReturn().response.status
        val staffList = mockMvc.get("/api/v1/transactions/merchants/MARKETPLACE-5") {
            with(neither)
        }.andReturn().response.status
        val staffDetail = mockMvc.get("/api/v1/transactions/merchants/MARKETPLACE-5/1001") {
            with(neither)
        }.andReturn().response.status

        assertEquals(HttpStatus.FORBIDDEN.value(), ownList)
        assertEquals(HttpStatus.FORBIDDEN.value(), ownDetail)
        assertEquals(HttpStatus.FORBIDDEN.value(), staffList)
        assertEquals(HttpStatus.FORBIDDEN.value(), staffDetail)
    }

    @Test
    fun `a seller can neither list nor read transactions`() {
        val seller = jwt()
            .jwt { it.claim("seller_id", "SELLER-5-1") }
            .authorities(SimpleGrantedAuthority("balance:read"))

        val ownList = mockMvc.get("/api/v1/transactions/merchants/me") { with(seller) }.andReturn().response.status
        val staffList = mockMvc.get(
            "/api/v1/transactions/merchants/MARKETPLACE-5"
        ) { with(seller) }.andReturn().response.status
        val staffDetail = mockMvc.get("/api/v1/transactions/merchants/MARKETPLACE-5/1001") {
            with(seller)
        }.andReturn().response.status

        assertEquals(HttpStatus.FORBIDDEN.value(), ownList)
        assertEquals(HttpStatus.FORBIDDEN.value(), staffList)
        assertEquals(HttpStatus.FORBIDDEN.value(), staffDetail)
    }

    @Test
    fun `without a token every transaction endpoint is 401`() {
        val ownList = mockMvc.get("/api/v1/transactions/merchants/me").andReturn().response.status
        val ownDetail = mockMvc.get("/api/v1/transactions/merchants/me/1001").andReturn().response.status
        val staffList = mockMvc.get("/api/v1/transactions/merchants/MARKETPLACE-5").andReturn().response.status
        val staffDetail = mockMvc.get("/api/v1/transactions/merchants/MARKETPLACE-5/1001").andReturn().response.status

        assertEquals(HttpStatus.UNAUTHORIZED.value(), ownList)
        assertEquals(HttpStatus.UNAUTHORIZED.value(), ownDetail)
        assertEquals(HttpStatus.UNAUTHORIZED.value(), staffList)
        assertEquals(HttpStatus.UNAUTHORIZED.value(), staffDetail)
    }
}
