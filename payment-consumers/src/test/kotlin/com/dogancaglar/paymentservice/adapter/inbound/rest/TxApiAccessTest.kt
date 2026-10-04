package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.config.SecurityConfig
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import com.dogancaglar.paymentservice.ports.inbound.usecases.TxUseCase
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
 * Who gets through to txs and journal entries: finance and admin only (ledger:read + merchant:all).
 * 403 = refused, 401 = no token; let through = 200 (or 404 when there is no such payment / tx).
 */
@WebMvcTest(TxController::class)
@Import(SecurityConfig::class)
// beans the controller's context needs; the tests never call them
@MockitoBean(types = [JwtDecoder::class])
class TxApiAccessTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var txUseCase: TxUseCase

    @Test
    fun `finance can read a payment's txs and one tx`() {
        val finance = jwt().authorities(SimpleGrantedAuthority("ledger:read"), SimpleGrantedAuthority("merchant:all"))
        // MARKETPLACE-5's payment 1001 has authorization tx 5001; payment 1009 and tx 5009 do not exist
        val authorization = Tx.AuthorizationTx(
            txId = TxId(5001),
            paymentId = PaymentId(1001),
            paymentIntentId = PaymentIntentId(1101),
            acquirerReference = "psp_1001",
            amount = Amount.of(3000, Currency("EUR")),
            createdAt = Instant.parse("2026-10-02T10:00:00Z")
        )
        `when`(txUseCase.findTxsOfPayment(PaymentId(1001), "MARKETPLACE-5")).thenReturn(listOf(authorization))
        `when`(txUseCase.getTx(TxId(5001), "MARKETPLACE-5")).thenReturn(authorization)

        val payment = mockMvc.get(
            "/api/v1/txs/merchants/MARKETPLACE-5/payments/1001"
        ) { with(finance) }.andReturn().response.status
        val missingPayment = mockMvc.get(
            "/api/v1/txs/merchants/MARKETPLACE-5/payments/1009"
        ) { with(finance) }.andReturn().response.status
        val tx = mockMvc.get("/api/v1/txs/merchants/MARKETPLACE-5/5001") { with(finance) }.andReturn().response.status
        val missingTx = mockMvc.get(
            "/api/v1/txs/merchants/MARKETPLACE-5/5009"
        ) { with(finance) }.andReturn().response.status

        assertEquals(HttpStatus.OK.value(), payment)
        assertEquals(HttpStatus.NOT_FOUND.value(), missingPayment)
        assertEquals(HttpStatus.OK.value(), tx)
        assertEquals(HttpStatus.NOT_FOUND.value(), missingTx)
    }

    @Test
    fun `support, without ledger read, reads no txs`() {
        val support = jwt().authorities(
            SimpleGrantedAuthority("transaction:read"),
            SimpleGrantedAuthority("balance:read"),
            SimpleGrantedAuthority("merchant:all")
        )

        val payment = mockMvc.get(
            "/api/v1/txs/merchants/MARKETPLACE-5/payments/1001"
        ) { with(support) }.andReturn().response.status
        val tx = mockMvc.get("/api/v1/txs/merchants/MARKETPLACE-5/5001") { with(support) }.andReturn().response.status

        assertEquals(HttpStatus.FORBIDDEN.value(), payment)
        assertEquals(HttpStatus.FORBIDDEN.value(), tx)
    }

    @Test
    fun `a merchant reads no txs, not even its own`() {
        val merchant = jwt().jwt { it.claim("merchant_id", "MARKETPLACE-5") }
            .authorities(SimpleGrantedAuthority("transaction:read"), SimpleGrantedAuthority("ledger:read"))

        val payment = mockMvc.get(
            "/api/v1/txs/merchants/MARKETPLACE-5/payments/1001"
        ) { with(merchant) }.andReturn().response.status
        val tx = mockMvc.get("/api/v1/txs/merchants/MARKETPLACE-5/5001") { with(merchant) }.andReturn().response.status

        assertEquals(HttpStatus.FORBIDDEN.value(), payment) // not staff (no merchant:all)
        assertEquals(HttpStatus.FORBIDDEN.value(), tx)
    }

    @Test
    fun `without a token txs are 401`() {
        val payment = mockMvc.get("/api/v1/txs/merchants/MARKETPLACE-5/payments/1001").andReturn().response.status
        val tx = mockMvc.get("/api/v1/txs/merchants/MARKETPLACE-5/5001").andReturn().response.status

        assertEquals(HttpStatus.UNAUTHORIZED.value(), payment)
        assertEquals(HttpStatus.UNAUTHORIZED.value(), tx)
    }
}
