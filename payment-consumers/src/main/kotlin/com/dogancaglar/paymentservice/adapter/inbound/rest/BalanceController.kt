package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.port.out.web.dto.BalanceDto
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Balance API (base URL .../api/v1). Who the caller is comes from the JWT:
 * - seller: role SELLER (user) or SELLER_API (client), claim seller_id
 * - merchant: role MERCHANT (client), claim merchant_id
 * - back office: role FINANCE or ADMIN
 */
@RestController
@RequestMapping("/api/v1")
class BalanceController(
    private val balanceService: BalanceService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** The caller's own balance: a seller's account, or a merchant's two payable accounts. */
    @PreAuthorize("hasAnyRole('SELLER', 'SELLER_API', 'MERCHANT')")
    @GetMapping("/balances/me")
    fun getMyBalance(authentication: JwtAuthenticationToken): ResponseEntity<BalanceDto> {
        if (hasRole(authentication, "SELLER") || hasRole(authentication, "SELLER_API")) {
            val sellerId = claim(authentication, "seller_id")
            logger.debug("📊 Balance of seller {} (own)", sellerId)
            return ResponseEntity.ok(balanceService.getSellerBalance(sellerId))
        }
        val merchantId = claim(authentication, "merchant_id")
        logger.debug("📊 Balance of merchant {} (own)", merchantId)
        return ResponseEntity.ok(balanceService.getMerchantBalance(merchantId))
    }

    /** One seller's balance: a merchant reads only its own sellers; finance/admin read any seller. */
    @PreAuthorize("hasAnyRole('MERCHANT', 'FINANCE', 'ADMIN')")
    @GetMapping("/balances/{sellerId}")
    fun getSellerBalance(
        @PathVariable sellerId: String,
        authentication: JwtAuthenticationToken
    ): ResponseEntity<BalanceDto> {
        if (hasRole(authentication, "FINANCE") || hasRole(authentication, "ADMIN")) {
            logger.debug("📊 Balance of seller {} (back office)", sellerId)
            return ResponseEntity.ok(balanceService.getSellerBalance(sellerId))
        }
        val merchantId = claim(authentication, "merchant_id")
        logger.debug("📊 Balance of seller {} (merchant {})", sellerId, merchantId)
        return ResponseEntity.ok(balanceService.getSellerBalanceForMerchant(sellerId, merchantId))
    }

    private fun hasRole(authentication: JwtAuthenticationToken, role: String): Boolean {
        for (authority in authentication.authorities) {
            if (authority.authority == "ROLE_$role") {
                return true
            }
        }
        return false
    }

    // A token without the owner claim cannot say whose balance it may read
    private fun claim(authentication: JwtAuthenticationToken, name: String): String {
        return authentication.token.claims[name] as? String
            ?: throw AccessDeniedException("Token has no $name claim")
    }
}
