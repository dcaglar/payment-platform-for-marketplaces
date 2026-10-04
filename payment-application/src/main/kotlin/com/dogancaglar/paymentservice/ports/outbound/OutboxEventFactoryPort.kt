package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.common.event.Event
import com.dogancaglar.paymentservice.application.events.PaymentBaseEvent
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent

interface OutboxEventFactoryPort {
    fun create(event: PaymentBaseEvent): OutboxEvent

    /** Any event, with the aggregate it belongs to and its Kafka partition key given explicitly. */
    fun create(event: Event, aggregateId: String, partitionKey: String): OutboxEvent
}
