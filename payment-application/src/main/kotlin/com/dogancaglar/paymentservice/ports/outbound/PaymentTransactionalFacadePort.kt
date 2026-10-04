// payment-application
package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent

interface PaymentTransactionalFacadePort {
    fun handleAuthorized(authorizedPaymentIntent: PaymentIntent, paymentAuthorizedOutboxEvent: OutboxEvent)
}
