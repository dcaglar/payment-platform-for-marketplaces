package com.dogancaglar.paymentservice.application.command

import com.dogancaglar.paymentservice.domain.model.payment.PaymentMethod
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId

data class AuthorizePaymentIntentCommand(
    val paymentIntentId: PaymentIntentId,
    /** The caller's merchant: only its own intents are found. */
    val merchantAccount: String,
    // Optional - for Stripe Payment Element, payment method is already attached
    val paymentMethod: PaymentMethod? = null
)
