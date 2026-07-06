package com.dogancaglar.paymentservice.infra.adapter.outbound.serialization

import com.dogancaglar.common.event.EventEnvelopeFactory
import com.dogancaglar.common.logging.EventLogContext
import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.application.events.PaymentBaseEvent
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import com.dogancaglar.paymentservice.ports.outbound.SerializationPort

class OutboxEventEventFactory(
    private val serializationPort: SerializationPort,
    ) : OutboxEventFactoryPort{
   override  fun  create(event: PaymentBaseEvent): OutboxEvent {
        // 1. Singleton Registry resolves the "Law"

        // 2. The lambda is executed here, at the moment of creation
        val partitionKey = getPartitionKey(event)

        // 3. Create standardized envelope
        val envelope = EventEnvelopeFactory.envelopeFor(
            data = event,
            aggregateId = event.publicPaymentIntentId,
            parentEventId = EventLogContext.getEventId())

        return OutboxEvent.Companion.createNew(
            oeid = event.paymentIntentId.toLong(),
            partitionKey = partitionKey,
            eventType = envelope.eventType,
            aggregateId = envelope.aggregateId,
            eventId = envelope.eventId,
            parentEventId = envelope.parentEventId,
            payload = serializationPort.toJson(envelope),
        )
    }


        private fun getPartitionKey(event: PaymentBaseEvent): String {
            return when (event) {
                is JournalEntriesRecorded -> event.merchantAccountId
                else -> event.paymentIntentId
            }
        }
}