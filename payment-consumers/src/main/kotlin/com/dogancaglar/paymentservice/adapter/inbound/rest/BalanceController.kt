package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.BalanceDto
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.PageDto
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Balance API (base URL .../api/v1/balances), permission balance:read. Each endpoint has one kind of caller:
 *   - a seller (claim seller_id): its own balance;
 *   - a merchant (claim merchant_id): its own balance, its sellers, one of its sellers (another merchant's: 404);
 *   - staff (merchant:all): the balance and the sellers of the merchant named in the path, any seller.
 * URL convention (also TransactionController): /<api>/merchants/me… = the token's merchant,
 * /<api>/merchants/{merchantAccount}… = staff.
 * See new-backoffice.md, "Security".
 */
@RestController
@RequestMapping("/api/v1")
class BalanceController(
    private val balanceService: BalanceService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // --- seller ---

    /** A seller's own balance (its one account). */
    @PreAuthorize("hasAuthority('balance:read') and principal.claims['seller_id'] != null")
    @GetMapping("/balances/sellers/me")
    fun getMySellerBalance(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<BalanceDto> {
        return ResponseEntity.ok(balanceService.getSellerBalance(jwt.getClaimAsString("seller_id")))
    }

    // --- merchant: always its own, from the token ---

    /** A merchant's own balance (its two payable accounts). */
    @PreAuthorize("hasAuthority('balance:read') and principal.claims['merchant_id'] != null")
    @GetMapping("/balances/merchants/me")
    fun getMyMerchantBalance(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<BalanceDto> {
        return ResponseEntity.ok(balanceService.getMerchantBalance(jwt.getClaimAsString("merchant_id")))
    }

    /** A merchant's own sellers with their balances, one page. */
    @PreAuthorize("hasAuthority('balance:read') and principal.claims['merchant_id'] != null")
    @GetMapping("/balances/merchants/me/sellers")
    fun getMySellerBalances(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<PageDto<BalanceDto>> {
        val sellers = balanceService.getSellerBalancesOfMerchant(jwt.getClaimAsString("merchant_id"), page, size)
        return ResponseEntity.ok(withDetailUrls(sellers, "/api/v1/balances/merchants/me/sellers/"))
    }

    /** One of the merchant's own sellers; another merchant's seller is not found (404). */
    @PreAuthorize("hasAuthority('balance:read') and principal.claims['merchant_id'] != null")
    @GetMapping("/balances/merchants/me/sellers/{sellerId}")
    fun getMySellersBalance(
        @PathVariable sellerId: String,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<BalanceDto> {
        return ResponseEntity.ok(
            balanceService.getSellerBalanceForMerchant(sellerId, jwt.getClaimAsString("merchant_id"))
        )
    }

    // --- staff: the merchant or seller is named in the path ---

    /** The named merchant's balance (its two payable accounts); an unknown merchant is not found (404). */
    @PreAuthorize("hasAuthority('balance:read') and hasAuthority('merchant:all')")
    @GetMapping("/balances/merchants/{merchantAccount}")
    fun getMerchantBalance(@PathVariable merchantAccount: String): ResponseEntity<BalanceDto> {
        return ResponseEntity.ok(balanceService.getMerchantBalance(merchantAccount))
    }

    /** The sellers of the named merchant with their balances, one page. */
    @PreAuthorize("hasAuthority('balance:read') and hasAuthority('merchant:all')")
    @GetMapping("/balances/merchants/{merchantAccount}/sellers")
    fun getSellerBalancesOfMerchant(
        @PathVariable merchantAccount: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageDto<BalanceDto>> {
        logger.debug("📊 Sellers of merchant {}, page {} (staff)", merchantAccount, page)
        val sellers = balanceService.getSellerBalancesOfMerchant(merchantAccount, page, size)
        return ResponseEntity.ok(withDetailUrls(sellers, "/api/v1/balances/sellers/"))
    }

    /** Any seller's balance. */
    @PreAuthorize("hasAuthority('balance:read') and hasAuthority('merchant:all')")
    @GetMapping("/balances/sellers/{sellerId}")
    fun getSellerBalance(@PathVariable sellerId: String): ResponseEntity<BalanceDto> {
        return ResponseEntity.ok(balanceService.getSellerBalance(sellerId))
    }

    /** Each seller in the page links to its balance, at the URL the same caller can read. */
    private fun withDetailUrls(sellers: PageDto<BalanceDto>, prefix: String): PageDto<BalanceDto> {
        val items = mutableListOf<BalanceDto>()
        for (seller in sellers.items) {
            items.add(seller.copy(detailUrl = prefix + seller.ownerId))
        }
        return sellers.copy(items = items)
    }
}
