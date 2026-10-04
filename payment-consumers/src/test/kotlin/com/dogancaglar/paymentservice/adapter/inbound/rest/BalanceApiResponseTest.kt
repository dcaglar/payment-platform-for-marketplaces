package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.config.SecurityConfig
import com.dogancaglar.paymentservice.domain.model.account.AccountStatus
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.ports.inbound.usecases.AccountBalanceReadUseCase
import com.dogancaglar.paymentservice.ports.outbound.AccountDirectoryPort
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * What the balance API answers once a caller is let through (BalanceApiAccessTest): the balances, 404, 400.
 * The real BalanceService runs; each test says which accounts and balances exist.
 */
@WebMvcTest(BalanceController::class)
@Import(SecurityConfig::class, BalanceService::class, BalanceApiExceptionHandler::class)
// beans the controller's context needs; the tests never call them
@MockitoBean(types = [JwtDecoder::class])
class BalanceApiResponseTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var accountDirectory: AccountDirectoryPort

    @MockitoBean
    private lateinit var balances: AccountBalanceReadUseCase

    @Test
    fun `a seller's own balance is its one seller account`() {
        val seller = jwt().jwt {
            it.claim(
                "seller_id",
                "SELLER-5-1"
            )
        }.authorities(SimpleGrantedAuthority("balance:read"))
        val sellerAccount = AccountProfile(
            "SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR",
            LedgerAccountType.SELLER_PAYABLE,
            "MARKETPLACE-5",
            "SELLER-5-1",
            Currency("EUR"),
            AccountStatus.ACTIVE
        )
        `when`(
            accountDirectory.getAccountProfilesBySubEntity(LedgerAccountType.SELLER_PAYABLE, "SELLER-5-1")
        ).thenReturn(listOf(sellerAccount))
        `when`(balances.getRealTimeBalance("SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR")).thenReturn(1400)

        mockMvc.get("/api/v1/balances/sellers/me") { with(seller) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.ownerType") { value("SELLER") } }
            .andExpect { jsonPath("$.ownerId") { value("SELLER-5-1") } }
            .andExpect { jsonPath("$.accounts.length()") { value(1) } }
            .andExpect { jsonPath("$.total") { value(1400) } }
    }

    @Test
    fun `a merchant's own balance lists direct and commission payable and their total`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("balance:read"))
        val direct = AccountProfile(
            "MERCHANT_DIRECT_PAYABLE.MARKETPLACE-5.EUR",
            LedgerAccountType.MERCHANT_DIRECT_PAYABLE,
            "MARKETPLACE-5",
            null,
            Currency("EUR"),
            AccountStatus.ACTIVE
        )
        val commission = AccountProfile(
            "MERCHANT_COMMISSION_PAYABLE.MARKETPLACE-5.EUR",
            LedgerAccountType.MERCHANT_COMMISSION_PAYABLE,
            "MARKETPLACE-5",
            null,
            Currency("EUR"),
            AccountStatus.ACTIVE
        )
        `when`(
            accountDirectory.getAccountProfilesByMaster(LedgerAccountType.MERCHANT_DIRECT_PAYABLE, "MARKETPLACE-5")
        ).thenReturn(listOf(direct))
        `when`(
            accountDirectory.getAccountProfilesByMaster(LedgerAccountType.MERCHANT_COMMISSION_PAYABLE, "MARKETPLACE-5")
        ).thenReturn(listOf(commission))
        `when`(balances.getRealTimeBalance("MERCHANT_DIRECT_PAYABLE.MARKETPLACE-5.EUR")).thenReturn(4950)
        `when`(balances.getRealTimeBalance("MERCHANT_COMMISSION_PAYABLE.MARKETPLACE-5.EUR")).thenReturn(150)

        mockMvc.get("/api/v1/balances/merchants/me") { with(merchant) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.ownerType") { value("MERCHANT") } }
            .andExpect { jsonPath("$.accounts[0].accountType") { value("MERCHANT_DIRECT_PAYABLE") } }
            .andExpect { jsonPath("$.accounts[0].balance") { value(4950) } }
            .andExpect { jsonPath("$.accounts[1].accountType") { value("MERCHANT_COMMISSION_PAYABLE") } }
            .andExpect { jsonPath("$.accounts[1].balance") { value(150) } }
            .andExpect { jsonPath("$.total") { value(5100) } }
    }

    @Test
    fun `a merchant's sellers come as a page, each with a link to its balance`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("balance:read"))
        val seller1 = AccountProfile(
            "SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR",
            LedgerAccountType.SELLER_PAYABLE,
            "MARKETPLACE-5",
            "SELLER-5-1",
            Currency("EUR"),
            AccountStatus.ACTIVE
        )
        val seller2 = AccountProfile(
            "SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-2.EUR",
            LedgerAccountType.SELLER_PAYABLE,
            "MARKETPLACE-5",
            "SELLER-5-2",
            Currency("EUR"),
            AccountStatus.ACTIVE
        )
        `when`(
            accountDirectory.getSubEntityAccountProfilesByMaster(LedgerAccountType.SELLER_PAYABLE, "MARKETPLACE-5")
        ).thenReturn(listOf(seller1, seller2))
        `when`(balances.getRealTimeBalance("SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR")).thenReturn(1400)

        // page 0 of size 1: the first seller, and a next page
        mockMvc.get("/api/v1/balances/merchants/me/sellers?page=0&size=1") { with(merchant) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.items.length()") { value(1) } }
            .andExpect { jsonPath("$.items[0].ownerId") { value("SELLER-5-1") } }
            .andExpect { jsonPath("$.items[0].total") { value(1400) } }
            .andExpect {
                jsonPath(
                    "$.items[0].detailUrl"
                ) { value("/api/v1/balances/merchants/me/sellers/SELLER-5-1") }
            }
            .andExpect { jsonPath("$.totalItems") { value(2) } }
            .andExpect { jsonPath("$.totalPages") { value(2) } }
            .andExpect { jsonPath("$.hasNext") { value(true) } }
            .andExpect { jsonPath("$.hasPrevious") { value(false) } }
    }

    @Test
    fun `support gets the sellers of the merchant it names`() {
        val support = jwt().authorities(SimpleGrantedAuthority("balance:read"), SimpleGrantedAuthority("merchant:all"))
        val seller = AccountProfile(
            "SELLER_PAYABLE.MARKETPLACE-1.SELLER-1-1.EUR",
            LedgerAccountType.SELLER_PAYABLE,
            "MARKETPLACE-1",
            "SELLER-1-1",
            Currency("EUR"),
            AccountStatus.ACTIVE
        )
        `when`(
            accountDirectory.getSubEntityAccountProfilesByMaster(LedgerAccountType.SELLER_PAYABLE, "MARKETPLACE-1")
        ).thenReturn(listOf(seller))
        `when`(balances.getRealTimeBalance("SELLER_PAYABLE.MARKETPLACE-1.SELLER-1-1.EUR")).thenReturn(700)

        mockMvc.get("/api/v1/balances/merchants/MARKETPLACE-1/sellers") { with(support) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.items[0].ownerId") { value("SELLER-1-1") } }
            .andExpect { jsonPath("$.items[0].detailUrl") { value("/api/v1/balances/sellers/SELLER-1-1") } }
            .andExpect { jsonPath("$.items[0].total") { value(700) } }
    }

    @Test
    fun `a page size above 100 gets 400`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("balance:read"))

        mockMvc.get("/api/v1/balances/merchants/me/sellers?size=101") { with(merchant) }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a merchant reads one of its sellers`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("balance:read"))
        val seller = AccountProfile(
            "SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR",
            LedgerAccountType.SELLER_PAYABLE,
            "MARKETPLACE-5",
            "SELLER-5-1",
            Currency("EUR"),
            AccountStatus.ACTIVE
        )
        `when`(
            accountDirectory.getAccountProfilesBySubEntity(LedgerAccountType.SELLER_PAYABLE, "SELLER-5-1")
        ).thenReturn(listOf(seller))
        `when`(balances.getRealTimeBalance("SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR")).thenReturn(1400)

        mockMvc.get("/api/v1/balances/merchants/me/sellers/SELLER-5-1") { with(merchant) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.ownerId") { value("SELLER-5-1") } }
            .andExpect { jsonPath("$.total") { value(1400) } }
    }

    @Test
    fun `another merchant's seller is not found for a merchant`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("balance:read"))
        val sellerOfMarketplace1 = AccountProfile(
            "SELLER_PAYABLE.MARKETPLACE-1.SELLER-1-1.EUR",
            LedgerAccountType.SELLER_PAYABLE,
            "MARKETPLACE-1",
            "SELLER-1-1",
            Currency("EUR"),
            AccountStatus.ACTIVE
        )
        `when`(
            accountDirectory.getAccountProfilesBySubEntity(LedgerAccountType.SELLER_PAYABLE, "SELLER-1-1")
        ).thenReturn(listOf(sellerOfMarketplace1))

        mockMvc.get("/api/v1/balances/merchants/me/sellers/SELLER-1-1") { with(merchant) }
            .andExpect { status { isNotFound() } }
            .andExpect { jsonPath("$.code") { value("NOT_FOUND") } }
    }

    @Test
    fun `support reads the balance of the merchant it names, and an unknown merchant is not found`() {
        val support = jwt().authorities(SimpleGrantedAuthority("balance:read"), SimpleGrantedAuthority("merchant:all"))
        val direct = AccountProfile(
            "MERCHANT_DIRECT_PAYABLE.MARKETPLACE-1.EUR",
            LedgerAccountType.MERCHANT_DIRECT_PAYABLE,
            "MARKETPLACE-1",
            null,
            Currency("EUR"),
            AccountStatus.ACTIVE
        )
        val commission = AccountProfile(
            "MERCHANT_COMMISSION_PAYABLE.MARKETPLACE-1.EUR",
            LedgerAccountType.MERCHANT_COMMISSION_PAYABLE,
            "MARKETPLACE-1",
            null,
            Currency("EUR"),
            AccountStatus.ACTIVE
        )
        `when`(
            accountDirectory.getAccountProfilesByMaster(LedgerAccountType.MERCHANT_DIRECT_PAYABLE, "MARKETPLACE-1")
        ).thenReturn(listOf(direct))
        `when`(
            accountDirectory.getAccountProfilesByMaster(LedgerAccountType.MERCHANT_COMMISSION_PAYABLE, "MARKETPLACE-1")
        ).thenReturn(listOf(commission))
        `when`(balances.getRealTimeBalance("MERCHANT_DIRECT_PAYABLE.MARKETPLACE-1.EUR")).thenReturn(800)
        `when`(balances.getRealTimeBalance("MERCHANT_COMMISSION_PAYABLE.MARKETPLACE-1.EUR")).thenReturn(200)
        // the directory knows no MARKETPLACE-9: it answers with no accounts

        mockMvc.get("/api/v1/balances/merchants/MARKETPLACE-1") { with(support) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.ownerId") { value("MARKETPLACE-1") } }
            .andExpect { jsonPath("$.total") { value(1000) } }
        mockMvc.get("/api/v1/balances/merchants/MARKETPLACE-9") { with(support) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `finance reads any merchant's seller`() {
        val finance = jwt().authorities(SimpleGrantedAuthority("balance:read"), SimpleGrantedAuthority("merchant:all"))
        val sellerOfMarketplace1 = AccountProfile(
            "SELLER_PAYABLE.MARKETPLACE-1.SELLER-1-1.EUR",
            LedgerAccountType.SELLER_PAYABLE,
            "MARKETPLACE-1",
            "SELLER-1-1",
            Currency("EUR"),
            AccountStatus.ACTIVE
        )
        `when`(
            accountDirectory.getAccountProfilesBySubEntity(LedgerAccountType.SELLER_PAYABLE, "SELLER-1-1")
        ).thenReturn(listOf(sellerOfMarketplace1))
        `when`(balances.getRealTimeBalance("SELLER_PAYABLE.MARKETPLACE-1.SELLER-1-1.EUR")).thenReturn(700)

        mockMvc.get("/api/v1/balances/sellers/SELLER-1-1") { with(finance) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.total") { value(700) } }
    }

    @Test
    fun `an unknown seller is not found`() {
        val finance = jwt().authorities(SimpleGrantedAuthority("balance:read"), SimpleGrantedAuthority("merchant:all"))
        // the directory knows no SELLER-9-9: it answers with no accounts

        mockMvc.get("/api/v1/balances/sellers/SELLER-9-9") { with(finance) }
            .andExpect { status { isNotFound() } }
    }
}
