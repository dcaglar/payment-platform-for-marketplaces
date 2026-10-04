package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.config.SecurityConfig
import com.dogancaglar.port.out.web.dto.PageDto
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

/**
 * Who gets through to the balance API: decided by the token alone (its permissions and claims).
 * 200 = let through, 403 = refused, 401 = no token. The service behind is a mock that answers nothing.
 */
@WebMvcTest(BalanceController::class)
@Import(SecurityConfig::class)
// beans the controller's context needs; the tests never call them
@MockitoBean(types = [JwtDecoder::class])
class BalanceApiAccessTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var balanceService: BalanceService

    @Test
    fun `a seller can read only its own balance`() {
        val seller = jwt().jwt {
            it.claim(
                "seller_id",
                "SELLER-5-1"
            )
        }.authorities(SimpleGrantedAuthority("balance:read"))

        val ownBalance = mockMvc.get("/api/v1/balances/sellers/me") { with(seller) }.andReturn().response.status
        val merchantBalance = mockMvc.get("/api/v1/balances/merchants/me") { with(seller) }.andReturn().response.status
        val merchantSellers = mockMvc.get(
            "/api/v1/balances/merchants/me/sellers"
        ) { with(seller) }.andReturn().response.status
        val merchantSeller = mockMvc.get(
            "/api/v1/balances/merchants/me/sellers/SELLER-5-1"
        ) { with(seller) }.andReturn().response.status
        val staffSellers = mockMvc.get(
            "/api/v1/balances/merchants/MARKETPLACE-5/sellers"
        ) { with(seller) }.andReturn().response.status
        val staffSeller = mockMvc.get(
            "/api/v1/balances/sellers/SELLER-5-1"
        ) { with(seller) }.andReturn().response.status

        assertEquals(HttpStatus.OK.value(), ownBalance)
        assertEquals(HttpStatus.FORBIDDEN.value(), merchantBalance) // no merchant_id
        assertEquals(HttpStatus.FORBIDDEN.value(), merchantSellers)
        assertEquals(HttpStatus.FORBIDDEN.value(), merchantSeller)
        assertEquals(HttpStatus.FORBIDDEN.value(), staffSellers) // not staff
        assertEquals(HttpStatus.FORBIDDEN.value(), staffSeller) // not even itself by id
    }

    @Test
    fun `a merchant can read its own balance, its sellers and one of them, and nothing of staff`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("balance:read"))
        `when`(
            balanceService.getSellerBalancesOfMerchant("MARKETPLACE-5", 0, 20)
        ).thenReturn(PageDto.of(emptyList(), 0, 20, 0))

        val ownBalance = mockMvc.get("/api/v1/balances/merchants/me") { with(merchant) }.andReturn().response.status
        val ownSellers = mockMvc.get(
            "/api/v1/balances/merchants/me/sellers"
        ) { with(merchant) }.andReturn().response.status
        val ownSeller = mockMvc.get(
            "/api/v1/balances/merchants/me/sellers/SELLER-5-1"
        ) { with(merchant) }.andReturn().response.status
        val sellerBalance = mockMvc.get("/api/v1/balances/sellers/me") { with(merchant) }.andReturn().response.status
        val staffMerchant = mockMvc.get(
            "/api/v1/balances/merchants/MARKETPLACE-5"
        ) { with(merchant) }.andReturn().response.status
        val staffSellers = mockMvc.get(
            "/api/v1/balances/merchants/MARKETPLACE-5/sellers"
        ) { with(merchant) }.andReturn().response.status
        val staffSeller = mockMvc.get(
            "/api/v1/balances/sellers/SELLER-5-1"
        ) { with(merchant) }.andReturn().response.status

        assertEquals(HttpStatus.OK.value(), ownBalance)
        assertEquals(HttpStatus.OK.value(), ownSellers)
        assertEquals(HttpStatus.OK.value(), ownSeller)
        assertEquals(HttpStatus.FORBIDDEN.value(), sellerBalance) // no seller_id
        assertEquals(HttpStatus.FORBIDDEN.value(), staffMerchant) // not staff, even naming itself
        assertEquals(HttpStatus.FORBIDDEN.value(), staffSellers)
        assertEquals(HttpStatus.FORBIDDEN.value(), staffSeller)
    }

    @Test
    fun `a merchant without balance read can read no balance at all`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("payment:write"))

        val ownBalance = mockMvc.get("/api/v1/balances/merchants/me") { with(merchant) }.andReturn().response.status
        val ownSellers = mockMvc.get(
            "/api/v1/balances/merchants/me/sellers"
        ) { with(merchant) }.andReturn().response.status
        val ownSeller = mockMvc.get(
            "/api/v1/balances/merchants/me/sellers/SELLER-5-1"
        ) { with(merchant) }.andReturn().response.status

        assertEquals(HttpStatus.FORBIDDEN.value(), ownBalance)
        assertEquals(HttpStatus.FORBIDDEN.value(), ownSellers)
        assertEquals(HttpStatus.FORBIDDEN.value(), ownSeller)
    }

    @Test
    fun `support can read any merchant's sellers and any seller, but has no balance of its own`() {
        val support = jwt().authorities(SimpleGrantedAuthority("balance:read"), SimpleGrantedAuthority("merchant:all"))
        `when`(
            balanceService.getSellerBalancesOfMerchant("MARKETPLACE-5", 0, 20)
        ).thenReturn(PageDto.of(emptyList(), 0, 20, 0))

        val anyMerchantBalance = mockMvc.get(
            "/api/v1/balances/merchants/MARKETPLACE-5"
        ) { with(support) }.andReturn().response.status
        val anyMerchantSellers = mockMvc.get(
            "/api/v1/balances/merchants/MARKETPLACE-5/sellers"
        ) { with(support) }.andReturn().response.status
        val anySeller = mockMvc.get("/api/v1/balances/sellers/SELLER-5-1") { with(support) }.andReturn().response.status
        val ownAsMerchant = mockMvc.get("/api/v1/balances/merchants/me") { with(support) }.andReturn().response.status
        val ownSellers = mockMvc.get(
            "/api/v1/balances/merchants/me/sellers"
        ) { with(support) }.andReturn().response.status
        val ownAsSeller = mockMvc.get("/api/v1/balances/sellers/me") { with(support) }.andReturn().response.status

        assertEquals(HttpStatus.OK.value(), anyMerchantBalance)
        assertEquals(HttpStatus.OK.value(), anyMerchantSellers)
        assertEquals(HttpStatus.OK.value(), anySeller)
        assertEquals(HttpStatus.FORBIDDEN.value(), ownAsMerchant) // no merchant_id
        assertEquals(HttpStatus.FORBIDDEN.value(), ownSellers)
        assertEquals(HttpStatus.FORBIDDEN.value(), ownAsSeller) // no seller_id
    }

    @Test
    fun `without a token every balance is 401`() {
        val sellerOwn = mockMvc.get("/api/v1/balances/sellers/me").andReturn().response.status
        val merchantOwn = mockMvc.get("/api/v1/balances/merchants/me").andReturn().response.status
        val merchantSellers = mockMvc.get("/api/v1/balances/merchants/me/sellers").andReturn().response.status
        val staffSellers = mockMvc.get("/api/v1/balances/merchants/MARKETPLACE-5/sellers").andReturn().response.status
        val staffSeller = mockMvc.get("/api/v1/balances/sellers/SELLER-5-1").andReturn().response.status

        assertEquals(HttpStatus.UNAUTHORIZED.value(), sellerOwn)
        assertEquals(HttpStatus.UNAUTHORIZED.value(), merchantOwn)
        assertEquals(HttpStatus.UNAUTHORIZED.value(), merchantSellers)
        assertEquals(HttpStatus.UNAUTHORIZED.value(), staffSellers)
        assertEquals(HttpStatus.UNAUTHORIZED.value(), staffSeller)
    }
}
