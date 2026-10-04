package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.payment.Payment
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId

interface PaymentRepository {
    fun save(payment: Payment): Payment
    fun findById(paymentId: PaymentId): Payment
    fun findByPaymentIntentId(paymentIntentId: PaymentIntentId): Payment?
    fun getMaxPaymentId(): PaymentId
    fun updatePayment(payment: Payment)
}
