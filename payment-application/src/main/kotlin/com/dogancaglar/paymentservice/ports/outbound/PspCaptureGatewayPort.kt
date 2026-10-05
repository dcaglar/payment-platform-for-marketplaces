package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.payment.Payment
import com.dogancaglar.paymentservice.domain.model.payment.PspCaptureGatewayResponse
import com.dogancaglar.paymentservice.domain.model.payment.PspModificationStatus
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import java.util.concurrent.CompletableFuture

/**
 * Captures and refunds at the PSP (real or simulated). A call that gives no normal answer fails with
 * PspTransientException, PspUnknownException or PspPermanentException
 * ([com.dogancaglar.paymentservice.domain.exception.ExternalPspException], naming the operation and the payment),
 * never with the PSP's own exceptions. Callers catch those classes.
 */
interface PspCaptureGatewayPort {

    fun capture(payment: Payment): CompletableFuture<PspCaptureGatewayResponse>

    /**
     * Dispatches an asynchronous refund action for a payment intent.
     */
    fun refund(paymentIntentId: PaymentIntentId): CompletableFuture<PspModificationStatus>
}
