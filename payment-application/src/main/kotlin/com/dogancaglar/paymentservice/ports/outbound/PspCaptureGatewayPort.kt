package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.payment.Payment
import com.dogancaglar.paymentservice.domain.model.payment.PspCaptureGatewayResponse
import com.dogancaglar.paymentservice.domain.model.payment.PspModificationStatus
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import java.util.concurrent.CompletableFuture

/**
 * Port for Payment Service Provider (PSP) operations.
 * Implementations can be real or simulated.
 */
interface PspCaptureGatewayPort {

    fun capture(payment: Payment): CompletableFuture<PspCaptureGatewayResponse>

    /**
     * Dispatches an asynchronous refund action for a payment intent.
     */
    fun refund(paymentIntentId: PaymentIntentId): CompletableFuture<PspModificationStatus>
}
