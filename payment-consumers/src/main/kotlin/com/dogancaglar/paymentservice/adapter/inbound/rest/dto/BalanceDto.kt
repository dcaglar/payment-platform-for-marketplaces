package com.dogancaglar.paymentservice.adapter.inbound.rest.dto

/**
 * The balance of one owner: a seller, or a merchant. Each payable account is listed with its
 * balance, plus the total. Amounts are in the smallest currency unit (cents); all accounts in one
 * response share one currency.
 *
 * A seller has one account (SELLER_PAYABLE). A merchant can sell directly and run a marketplace,
 * so it has two: MERCHANT_DIRECT_PAYABLE and MERCHANT_COMMISSION_PAYABLE.
 */
data class BalanceDto(
    val ownerType: OwnerType,
    val ownerId: String,
    val currency: CurrencyEnum,
    val total: Long,
    val accounts: List<AccountBalanceDto>,
    /** In a list of sellers: where this seller's own balance is (GET /api/v1/balances/{sellerId}). */
    val detailUrl: String? = null
)

data class AccountBalanceDto(
    val accountType: String,
    val accountCode: String,
    val balance: Long
)

enum class OwnerType {
    SELLER, MERCHANT
}

enum class CurrencyEnum {
    EUR, USD, GBP
}
