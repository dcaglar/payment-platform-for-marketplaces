package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.common.db.converter.OutboxEventEntityMapper
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.edge.LocalOutboxWriterMapper
import com.dogancaglar.paymentservice.ports.outbound.LocalOutboxWriterPort
import org.springframework.stereotype.Repository

@Repository("localOutboxWriterAdapter")
class LocalOutboxWriterAdapter(
    private val localOutboxWriterMapper: LocalOutboxWriterMapper
) : LocalOutboxWriterPort {


    override fun save(event: OutboxEvent): OutboxEvent {
        localOutboxWriterMapper.insertOutboxEvent(OutboxEventEntityMapper.toEntity(event))
        return event
    }

    override fun saveAll(events: List<OutboxEvent>): List<OutboxEvent> {
        if (events.isNotEmpty()) {
            localOutboxWriterMapper.insertAllOutboxEvents(events.map(OutboxEventEntityMapper::toEntity))
        }
        return events
    }

    override fun hasPendingEvents(): Boolean {
        return localOutboxWriterMapper.checkPendingEventsExist()
    }
}