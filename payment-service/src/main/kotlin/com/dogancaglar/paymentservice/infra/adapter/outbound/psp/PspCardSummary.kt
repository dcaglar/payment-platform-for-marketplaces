package com.dogancaglar.paymentservice.infra.adapter.outbound.psp

import com.dogancaglar.paymentservice.domain.model.payment.CardBrand
import com.dogancaglar.paymentservice.domain.model.payment.CardSummary

/** A PSP's card details (brand name, last 4) as our CardSummary; null when the PSP gave none or no valid last 4. */
object PspCardSummary {

    fun of(brand: String?, last4: String?): CardSummary? {
        if (brand == null || last4 == null || !last4.matches(Regex("^[0-9]{4}$"))) {
            return null
        }
        return CardSummary.of(brandOf(brand), last4)
    }

    // PSPs name brands in lower case ("visa", "mastercard", "amex"), some with spaces or dashes
    private fun brandOf(name: String): CardBrand {
        val key = name.lowercase().replace(" ", "").replace("_", "").replace("-", "")
        return when (key) {
            "visa" -> CardBrand.VISA
            "mastercard", "mc" -> CardBrand.MASTERCARD
            "amex", "americanexpress" -> CardBrand.AMEX
            else -> CardBrand.OTHER
        }
    }
}
