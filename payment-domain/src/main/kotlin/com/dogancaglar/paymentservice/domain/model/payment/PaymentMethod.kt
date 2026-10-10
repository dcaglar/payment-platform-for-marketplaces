package com.dogancaglar.paymentservice.domain.model.payment

sealed class PaymentMethod {
    /** The PSP's token for the card (e.g. pm_...); no card data, no CVC. */
    data class CardToken(
        val token: String
    ) : PaymentMethod()
}
