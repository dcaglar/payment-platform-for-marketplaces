package com.dogancaglar.paymentservice.domain.model.payment

import com.dogancaglar.paymentservice.domain.exception.PaymentDomainException
import com.dogancaglar.paymentservice.domain.model.common.require

/**
 * The only card data we keep: its brand and its last 4 digits (e.g. VISA 4242), as the PSP reports them on
 * authorization. Never the full number, the CVC or the expiry date.
 */
data class CardSummary private constructor(
    val brand: CardBrand,
    val last4: String
) {
    companion object {
        fun of(brand: CardBrand, last4: String): CardSummary {
            require(last4.matches(Regex("^[0-9]{4}$"))) {
                PaymentDomainException.InvariantViolationException("Card last 4 must be 4 digits")
            }
            return CardSummary(brand, last4)
        }
    }
}

enum class CardBrand {
    VISA,
    MASTERCARD,
    AMEX,
    OTHER
}
