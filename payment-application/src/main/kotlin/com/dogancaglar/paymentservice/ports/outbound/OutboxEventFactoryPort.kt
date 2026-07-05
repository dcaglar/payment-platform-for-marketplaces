package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.application.events.PaymentBaseEvent
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent

interface OutboxEventFactoryPort {
    fun create(event: PaymentBaseEvent): OutboxEvent

}