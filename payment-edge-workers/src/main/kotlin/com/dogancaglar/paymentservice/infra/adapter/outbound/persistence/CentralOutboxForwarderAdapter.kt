package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.common.db.converter.OutboxEventEntityMapper
import com.dogancaglar.common.time.Utc
import com.dogancaglar.common.db.entity.OutboxEventEntity
import com.dogancaglar.common.db.entity.EdgeWatermarkEntity
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.CentralOutboxForwarderMapper
import com.dogancaglar.paymentservice.ports.outbound.CentralOutboxForwarderPort
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import org.springframework.stereotype.Repository

import java.time.Instant

@Repository
class CentralOutboxForwarderAdapter(
    private val mapper: CentralOutboxForwarderMapper
) : CentralOutboxForwarderPort {

    override fun insertBatch(edgeNodeId: String, entries: List<OutboxEvent>) {
        if (entries.isEmpty()) return
        val entities = entries.map {
            OutboxEventEntityMapper.toEntity(
                OutboxEvent.createNew(
                    it.oeid,
                    it.partitionKey,
                    it.eventType,
                    it.aggregateId,
                    it.traceId,
                    it.eventId,
                    it.parentEventId,
                    it.payload
                )
            )
        }
        mapper.insertBatch(entities)
    }

    override fun updateWatermark(edgeNodeId: String, forwardedUpTo: Instant) {
        mapper.upsert(EdgeWatermarkEntity(edgeNodeId, forwardedUpTo))
    }

    override fun isSchemaReady():Boolean{
        return mapper.isSchemaReady()
    }

    override fun deleteWatermark(edgeNodeId: String) {
        mapper.deleteWatermark(edgeNodeId)
    }
}
