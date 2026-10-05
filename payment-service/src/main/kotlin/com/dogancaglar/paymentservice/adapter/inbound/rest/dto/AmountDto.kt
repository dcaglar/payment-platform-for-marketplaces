package com.dogancaglar.paymentservice.adapter.inbound.rest.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * Amount DTO using smallest currency unit (cents, pence, etc.)
 *
 * Examples:
 * - $0.99 USD = AmountDto(quantity = 99, currency = USD)
 * - $20.00 USD = AmountDto(quantity = 2000, currency = USD)
 * - €15.50 EUR = AmountDto(quantity = 1550, currency = EUR)
 */
data class AmountDto(
    @field:NotNull
    @field:Min(value = 1, message = "Amount must be greater than zero")
    val quantity: Long,

    @field:NotNull
    val currency: CurrencyEnum
)

enum class CurrencyEnum {
    EUR, USD, GBP
}
