package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent

interface LocalOutboxWriterPort {
    fun save(event: OutboxEvent): OutboxEvent
    fun saveAll(events: List<OutboxEvent>): List<OutboxEvent>
    fun hasPendingEvents(): Boolean
}
