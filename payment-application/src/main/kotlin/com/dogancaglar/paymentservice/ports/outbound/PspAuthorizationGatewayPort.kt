package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentMethod
import java.util.concurrent.CompletableFuture

// todo remove idempote4ncy keyparam
interface PspAuthorizationGatewayPort {
    fun createPaymentIntent(paymentIntent: PaymentIntent): CompletableFuture<PaymentIntent> // "pi_..."
    fun authorizePaymentIntent(paymentIntent: PaymentIntent, token: PaymentMethod?): CompletableFuture<PaymentIntent>
    fun retrieveClientSecret(
        pspReference: String
    ): CompletableFuture<String>? // Retrieve clientSecret from Stripe by pspReference
}
