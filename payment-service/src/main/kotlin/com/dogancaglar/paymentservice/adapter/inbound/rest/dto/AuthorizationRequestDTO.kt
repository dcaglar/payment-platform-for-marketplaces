package com.dogancaglar.paymentservice.adapter.inbound.rest.dto

data class AuthorizationRequestDTO(
    // Optional - for Stripe Payment Element, payment method is already attached
    val paymentMethod: PaymentMethodDTO? = null
)
