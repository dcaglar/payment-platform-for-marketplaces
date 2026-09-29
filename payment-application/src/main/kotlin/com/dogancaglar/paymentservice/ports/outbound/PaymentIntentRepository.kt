package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import java.time.Instant



interface PaymentIntentRepository {
    fun save(paymentIntent: PaymentIntent): PaymentIntent
    fun findById(paymentIntentId: PaymentIntentId): PaymentIntent?
    fun getMaxPaymentIntentId(): PaymentIntentId
    fun updatePaymentIntent(paymentIntent: PaymentIntent)
    fun tryMarkPendingAuth(id: PaymentIntentId, now: Instant): Boolean
    fun updatePspReference(paymentIntentId: Long, pspReference: String, now: Instant)
}
