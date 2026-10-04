package com.dogancaglar.paymentservice.adapter.inbound.rest.dto

data class AuthorizationRequestDTO(
    val paymentMethod: PaymentMethodDTO? = null // Optional - for Stripe Payment Element, payment method is already attached
)
