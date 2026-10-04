package com.dogancaglar.paymentservice.domain.model.payment

sealed class PaymentMethod {
    data class CardToken(
        val token: String,
        val cvc: String?
    ) : PaymentMethod()
}
