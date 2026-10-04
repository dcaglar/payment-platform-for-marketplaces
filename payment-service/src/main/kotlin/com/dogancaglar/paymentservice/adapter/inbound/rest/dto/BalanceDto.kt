package com.dogancaglar.port.out.web.dto

/**
 * Balance DTO representing a merchant's account balance.
 *
 * The balance is returned in the smallest currency unit (cents, pence, etc.)
 * and includes the currency for proper formatting.
 *
 * Examples:
 * - $1,234.56 USD = BalanceDto(balance = 123456, currency = USD)
 * - €500.00 EUR = BalanceDto(balance = 50000, currency = EUR)
 */
data class BalanceDto(
    val balance: Long,
    val currency: CurrencyEnum,
    val accountCode: String,
    val sellerId: String
)
