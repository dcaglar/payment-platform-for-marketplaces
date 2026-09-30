package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.config.SecurityConfig
import com.dogancaglar.paymentservice.domain.model.ledger.AccountCategory
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.AccountStatus
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.ports.inbound.usecases.AccountBalanceReadUseCase
import com.dogancaglar.paymentservice.ports.outbound.AccountDirectoryPort
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant

/**
 * The balance API (base URL .../api/v1) through the real security filter chain, the real Keycloak
 * role mapping, the real BalanceService (merchant ownership check) and the 404 mapping.
 * Stubbed: the JWT signature check (a test token string becomes a Keycloak-shaped JWT), and the
 * account directory + balance reads (seed-like data below).
 *
 * Data: MARKETPLACE-5 owns SELLER-5-1 (1400); MARKETPLACE-1 owns SELLER-1-1 (700);
 * MARKETPLACE-5's direct payable is 4950 and its commission payable 150.
 */
@WebMvcTest(BalanceController::class)
@Import(SecurityConfig::class, BalanceService::class, BalanceApiExceptionHandler::class)
class BalanceApiSecurityTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var accountDirectory: AccountDirectoryPort

    @MockitoBean
    private lateinit var balanceReads: AccountBalanceReadUseCase

    @BeforeEach
    fun setUp() {
        tokenFor("seller-token", listOf("SELLER"), "seller_id", "SELLER-5-1")
        tokenFor("seller-api-token", listOf("SELLER_API"), "seller_id", "SELLER-5-1")
        tokenFor("merchant-token", listOf("MERCHANT"), "merchant_id", "MARKETPLACE-5")
        tokenFor("merchant-without-id-token", listOf("MERCHANT"), null, null)
        tokenFor("finance-token", listOf("FINANCE"), null, null)
        tokenFor("checkout-token", listOf("payment:write"), null, null)

        sellerAccount("SELLER-5-1", "MARKETPLACE-5", 1400)
        sellerAccount("SELLER-1-1", "MARKETPLACE-1", 700)
        merchantAccount(AccountType.MERCHANT_DIRECT_PAYABLE, "MARKETPLACE-5", 4950)
        merchantAccount(AccountType.MERCHANT_COMMISSION_PAYABLE, "MARKETPLACE-5", 150)
        // any other lookup returns an empty list: the id does not exist
    }

    // --- GET /balances/me: your own balance ---

    @Test
    fun `seller user reads its own balance`() {
        mockMvc.get("/api/v1/balances/me") { header("Authorization", "Bearer seller-token") }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.ownerType") { value("SELLER") } }
            .andExpect { jsonPath("$.ownerId") { value("SELLER-5-1") } }
            .andExpect { jsonPath("$.total") { value(1400) } }
            .andExpect { jsonPath("$.accounts.length()") { value(1) } }
    }

    @Test
    fun `seller api client reads its own balance`() {
        mockMvc.get("/api/v1/balances/me") { header("Authorization", "Bearer seller-api-token") }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.ownerId") { value("SELLER-5-1") } }
    }

    @Test
    fun `merchant reads its own balance, direct and commission payable`() {
        mockMvc.get("/api/v1/balances/me") { header("Authorization", "Bearer merchant-token") }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.ownerType") { value("MERCHANT") } }
            .andExpect { jsonPath("$.ownerId") { value("MARKETPLACE-5") } }
            .andExpect { jsonPath("$.total") { value(5100) } }
            .andExpect { jsonPath("$.accounts[0].accountType") { value("MERCHANT_DIRECT_PAYABLE") } }
            .andExpect { jsonPath("$.accounts[0].balance") { value(4950) } }
            .andExpect { jsonPath("$.accounts[1].accountType") { value("MERCHANT_COMMISSION_PAYABLE") } }
            .andExpect { jsonPath("$.accounts[1].balance") { value(150) } }
    }

    @Test
    fun `merchant token without merchant_id is refused`() {
        mockMvc.get("/api/v1/balances/me") { header("Authorization", "Bearer merchant-without-id-token") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `finance has no own balance`() {
        mockMvc.get("/api/v1/balances/me") { header("Authorization", "Bearer finance-token") }
            .andExpect { status { isForbidden() } }
    }

    // --- GET /balances/{sellerId}: one seller's balance ---

    @Test
    fun `merchant reads its own seller`() {
        mockMvc.get("/api/v1/balances/SELLER-5-1") { header("Authorization", "Bearer merchant-token") }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.ownerId") { value("SELLER-5-1") } }
            .andExpect { jsonPath("$.total") { value(1400) } }
    }

    @Test
    fun `merchant cannot read another merchant's seller`() {
        mockMvc.get("/api/v1/balances/SELLER-1-1") { header("Authorization", "Bearer merchant-token") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `unknown seller is not found`() {
        mockMvc.get("/api/v1/balances/SELLER-9-9") { header("Authorization", "Bearer merchant-token") }
            .andExpect { status { isNotFound() } }
            .andExpect { jsonPath("$.code") { value("NOT_FOUND") } }
    }

    @Test
    fun `finance reads any seller`() {
        mockMvc.get("/api/v1/balances/SELLER-1-1") { header("Authorization", "Bearer finance-token") }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.ownerId") { value("SELLER-1-1") } }
            .andExpect { jsonPath("$.total") { value(700) } }
    }

    @Test
    fun `seller cannot read a seller by id, not even itself`() {
        mockMvc.get("/api/v1/balances/SELLER-5-1") { header("Authorization", "Bearer seller-token") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `checkout token cannot read balances`() {
        mockMvc.get("/api/v1/balances/SELLER-1-1") { header("Authorization", "Bearer checkout-token") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `no token is rejected`() {
        mockMvc.get("/api/v1/balances/me")
            .andExpect { status { isUnauthorized() } }
    }

    private fun tokenFor(token: String, roles: List<String>, ownerClaim: String?, ownerId: String?) {
        val builder = Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .subject(token)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600))
            .claim("realm_access", mapOf("roles" to roles))
        if (ownerClaim != null && ownerId != null) {
            builder.claim(ownerClaim, ownerId)
        }
        `when`(jwtDecoder.decode(token)).thenReturn(builder.build())
    }

    private fun sellerAccount(sellerId: String, merchantId: String, balance: Long) {
        val code = "SELLER_PAYABLE.$merchantId.$sellerId.EUR"
        val profile = profile(code, AccountType.SELLER_PAYABLE, merchantId, sellerId)
        `when`(accountDirectory.getAccountProfilesBySubEntity(AccountType.SELLER_PAYABLE, sellerId)).thenReturn(listOf(profile))
        `when`(balanceReads.getRealTimeBalance(code)).thenReturn(balance)
    }

    private fun merchantAccount(type: AccountType, merchantId: String, balance: Long) {
        val code = "${type.name}.$merchantId.EUR"
        val profile = profile(code, type, merchantId, null)
        `when`(accountDirectory.getAccountProfilesByMaster(type, merchantId)).thenReturn(listOf(profile))
        `when`(balanceReads.getRealTimeBalance(code)).thenReturn(balance)
    }

    private fun profile(code: String, type: AccountType, merchantId: String, sellerId: String?): AccountProfile =
        AccountProfile(
            accountCode = code,
            type = type,
            masterAccountCode = merchantId,
            subEntityId = sellerId,
            currency = Currency("EUR"),
            category = AccountCategory.LIABILITY,
            country = "NL",
            status = AccountStatus.ACTIVE
        )
}
