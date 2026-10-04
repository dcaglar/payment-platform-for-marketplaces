package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.config.SecurityConfig
import com.dogancaglar.paymentservice.ports.inbound.usecases.RequestAccountCreationUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * Who may create merchants (POST /api/v1/accounts): decided by the token alone, permission account:write.
 * 202 = let through, 403 = refused, 401 = no token. The use case behind is a mock that does nothing.
 */
@WebMvcTest(AccountController::class)
@Import(SecurityConfig::class, AccountApiOrchestrator::class, AccountApiExceptionHandler::class)
// beans the controller's context needs; the tests never call them
@MockitoBean(types = [JwtDecoder::class, RequestAccountCreationUseCase::class])
class AccountApiAccessTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `an admin can create a merchant`() {
        val admin = jwt().authorities(SimpleGrantedAuthority("account:write"), SimpleGrantedAuthority("merchant:all"))
        val newMerchant = """
            { "merchantAccountCode": "MARKETPLACE-9", "legalName": "Marketplace Nine B.V.",
              "address": { "line1": "Damrak 9", "city": "Amsterdam", "postalCode": "1012 LG", "country": "NL" },
              "industry": "5399", "currency": "EUR", "platformFeeFixed": 30, "platformFeeBps": 150,
              "sellerAccountCodes": [ "SELLER-9-1" ] }
        """.trimIndent()

        val status = mockMvc.post("/api/v1/accounts") {
            with(admin)
            contentType = MediaType.APPLICATION_JSON
            content = newMerchant
        }.andReturn().response.status

        assertEquals(HttpStatus.ACCEPTED.value(), status)
    }

    @Test
    fun `finance cannot create a merchant`() {
        val finance = jwt().authorities(
            SimpleGrantedAuthority("transaction:read"),
            SimpleGrantedAuthority("ledger:read"),
            SimpleGrantedAuthority("merchant:all")
        )

        val status = mockMvc.post("/api/v1/accounts") {
            with(finance)
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andReturn().response.status

        assertEquals(HttpStatus.FORBIDDEN.value(), status)
    }

    @Test
    fun `a merchant cannot create a merchant`() {
        val merchant = jwt().jwt {
            it.claim("merchant_id", "MARKETPLACE-5")
        }.authorities(SimpleGrantedAuthority("payment:write"), SimpleGrantedAuthority("transaction:read"))

        val status = mockMvc.post("/api/v1/accounts") {
            with(merchant)
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andReturn().response.status

        assertEquals(HttpStatus.FORBIDDEN.value(), status)
    }

    @Test
    fun `without a token creating a merchant is 401`() {
        val status = mockMvc.post("/api/v1/accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andReturn().response.status

        assertEquals(HttpStatus.UNAUTHORIZED.value(), status)
    }
}
