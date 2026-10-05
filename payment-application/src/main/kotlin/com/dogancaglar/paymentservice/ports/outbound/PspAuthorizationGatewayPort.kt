package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentMethod
import java.util.concurrent.CompletableFuture

/**
 * Creates and authorizes payments at the PSP. A decline is an answer (the returned intent is DECLINED); a call
 * that gives no normal answer fails with PspTransientException, PspUnknownException or PspPermanentException
 * ([com.dogancaglar.paymentservice.domain.exception.ExternalPspException], naming the operation and the payment),
 * never with the PSP's own exceptions. Callers catch those classes.
 */
interface PspAuthorizationGatewayPort {
    fun createPaymentIntent(paymentIntent: PaymentIntent): CompletableFuture<PaymentIntent> // "pi_..."
    fun authorizePaymentIntent(paymentIntent: PaymentIntent, token: PaymentMethod?): CompletableFuture<PaymentIntent>

    /** The client secret of an intent the PSP already created (it has a pspReference); never persisted. */
    fun retrieveClientSecret(paymentIntent: PaymentIntent): CompletableFuture<String>?
}
