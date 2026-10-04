package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.ports.outbound.LocalOutboxWriterPort
import com.dogancaglar.paymentservice.ports.outbound.PaymentIntentRepository
import com.dogancaglar.paymentservice.ports.outbound.PaymentTransactionalFacadePort
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PaymentTransactionalFacadeAdapter(
    private val paymentIntentRepository: PaymentIntentRepository,
    @Qualifier("localOutboxWriterAdapter") private val localOutboxWriterPort: LocalOutboxWriterPort,
) : PaymentTransactionalFacadePort {

    @Transactional(timeout = 2)
    override fun handleAuthorized(authorizedPaymentIntent: PaymentIntent, paymentAuthorizedOutboxEvent: OutboxEvent) {
        paymentIntentRepository.updatePaymentIntent(authorizedPaymentIntent)
        localOutboxWriterPort.saveAll(listOf(paymentAuthorizedOutboxEvent))
    }
}
