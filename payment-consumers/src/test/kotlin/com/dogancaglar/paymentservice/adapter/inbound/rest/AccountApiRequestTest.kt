package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.application.command.CreateAccountCommand
import com.dogancaglar.paymentservice.config.SecurityConfig
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.ports.inbound.usecases.RequestAccountCreationUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * What a create-merchant request (POST /api/v1/accounts) turns into, sent by an admin (who may: AccountApiAccessTest):
 * the command queued for the consumer, or 400 and nothing queued when the request is invalid.
 */
@WebMvcTest(AccountController::class)
@Import(SecurityConfig::class, AccountApiOrchestrator::class, AccountApiExceptionHandler::class)
// beans the controller's context needs; the tests never call them
@MockitoBean(types = [JwtDecoder::class])
class AccountApiRequestTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var requestAccountCreation: RequestAccountCreationUseCase

    @Test
    fun `a merchant with two sellers is queued as one command with exactly what was sent`() {
        val admin = jwt().authorities(SimpleGrantedAuthority("account:write"))
        val marketplace9WithTwoSellers = """
            { "merchantAccountCode": "MARKETPLACE-9", "legalName": "Marketplace Nine B.V.",
              "address": { "line1": "Damrak 9", "city": "Amsterdam", "postalCode": "1012 LG", "country": "NL" },
              "industry": "5399", "currency": "EUR", "platformFeeFixed": 30, "platformFeeBps": 150,
              "sellerAccountCodes": [ "SELLER-9-1", "SELLER-9-2" ] }
        """.trimIndent()

        mockMvc.post("/api/v1/accounts") {
            with(admin)
            contentType = MediaType.APPLICATION_JSON
            content = marketplace9WithTwoSellers
        }.andExpect { status { isAccepted() } }
            .andExpect { jsonPath("$.merchantAccountCode") { value("MARKETPLACE-9") } }
            .andExpect { jsonPath("$.status") { value("ACCEPTED") } }

        // what reached the use case: exactly one command
        val calls = mockingDetails(requestAccountCreation).invocations
        assertEquals(1, calls.size)
        val command = calls.first().arguments[0] as CreateAccountCommand
        assertEquals("MARKETPLACE-9", command.merchantAccountCode)
        assertEquals("Marketplace Nine B.V.", command.legalName)
        assertEquals(Currency("EUR"), command.currency)
        assertEquals(30L, command.platformFee.fixed.quantity)
        assertEquals(150, command.platformFee.basisPoints)
        assertEquals(listOf("SELLER-9-1", "SELLER-9-2"), command.sellerAccountCodes)
        assertEquals(true, command.isAutoCaptured) // not sent: defaults to true
        assertEquals(false, command.isAutoSettled) // not sent: defaults to false
    }

    @Test
    fun `auto capture and auto settle are taken as sent`() {
        val admin = jwt().authorities(SimpleGrantedAuthority("account:write"))
        val manualCaptureAutoSettle = """
            { "merchantAccountCode": "MARKETPLACE-9", "legalName": "Marketplace Nine B.V.",
              "address": { "line1": "Damrak 9", "city": "Amsterdam", "postalCode": "1012 LG", "country": "NL" },
              "industry": "5399", "currency": "EUR", "platformFeeFixed": 30, "platformFeeBps": 150,
              "isAutoCaptured": false, "isAutoSettled": true,
              "sellerAccountCodes": [ "SELLER-9-1" ] }
        """.trimIndent()

        mockMvc.post("/api/v1/accounts") {
            with(admin)
            contentType = MediaType.APPLICATION_JSON
            content = manualCaptureAutoSettle
        }.andExpect { status { isAccepted() } }

        val command = mockingDetails(requestAccountCreation).invocations.first().arguments[0] as CreateAccountCommand
        assertEquals(false, command.isAutoCaptured)
        assertEquals(true, command.isAutoSettled)
    }

    @Test
    fun `a lowercase currency gets 400 and nothing is queued`() {
        val admin = jwt().authorities(SimpleGrantedAuthority("account:write"))
        val lowercaseCurrency = """
            { "merchantAccountCode": "MARKETPLACE-9", "legalName": "Marketplace Nine B.V.",
              "address": { "line1": "Damrak 9", "city": "Amsterdam", "postalCode": "1012 LG", "country": "NL" },
              "industry": "5399", "currency": "eur", "platformFeeFixed": 30, "platformFeeBps": 150,
              "sellerAccountCodes": [ "SELLER-9-1" ] }
        """.trimIndent()

        mockMvc.post("/api/v1/accounts") {
            with(admin)
            contentType = MediaType.APPLICATION_JSON
            content = lowercaseCurrency
        }.andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.code") { value("VALIDATION_ERROR") } }

        assertEquals(0, mockingDetails(requestAccountCreation).invocations.size) // nothing queued
    }

    @Test
    fun `a merchant code with a dot gets 400 and nothing is queued`() {
        val admin = jwt().authorities(SimpleGrantedAuthority("account:write"))
        val codeWithDot = """
            { "merchantAccountCode": "MARKET.PLACE", "legalName": "Marketplace Nine B.V.",
              "address": { "line1": "Damrak 9", "city": "Amsterdam", "postalCode": "1012 LG", "country": "NL" },
              "industry": "5399", "currency": "EUR", "platformFeeFixed": 30, "platformFeeBps": 150,
              "sellerAccountCodes": [ "SELLER-9-1" ] }
        """.trimIndent()

        mockMvc.post("/api/v1/accounts") {
            with(admin)
            contentType = MediaType.APPLICATION_JSON
            content = codeWithDot
        }.andExpect { status { isBadRequest() } }

        assertEquals(0, mockingDetails(requestAccountCreation).invocations.size) // nothing queued
    }

    @Test
    fun `the same seller twice gets 400 and nothing is queued`() {
        val admin = jwt().authorities(SimpleGrantedAuthority("account:write"))
        val sameSellerTwice = """
            { "merchantAccountCode": "MARKETPLACE-9", "legalName": "Marketplace Nine B.V.",
              "address": { "line1": "Damrak 9", "city": "Amsterdam", "postalCode": "1012 LG", "country": "NL" },
              "industry": "5399", "currency": "EUR", "platformFeeFixed": 30, "platformFeeBps": 150,
              "sellerAccountCodes": [ "SELLER-9-1", "SELLER-9-1" ] }
        """.trimIndent()

        mockMvc.post("/api/v1/accounts") {
            with(admin)
            contentType = MediaType.APPLICATION_JSON
            content = sameSellerTwice
        }.andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.code") { value("VALIDATION_ERROR") } }

        assertEquals(0, mockingDetails(requestAccountCreation).invocations.size) // nothing queued
    }
}
