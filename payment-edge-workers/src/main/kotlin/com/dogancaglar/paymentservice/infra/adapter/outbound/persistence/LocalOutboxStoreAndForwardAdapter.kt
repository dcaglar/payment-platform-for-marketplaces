package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.common.db.converter.OutboxEventEntityMapper
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.LocalOutboxMapperForEdgeWorker
import com.dogancaglar.paymentservice.ports.outbound.LocalOutboxStoreAndForwardPort
import org.springframework.stereotype.Repository

@Repository("localOutboxStoreAndForwardPort")
class LocalOutboxStoreAndForwardAdapter(
    private val mapper: LocalOutboxMapperForEdgeWorker
) : LocalOutboxStoreAndForwardPort {

    override fun findEligible(batchSize: Int, workerId: String): List<OutboxEvent> =
        mapper.findBatchForDispatch(batchSize, workerId)
            .map(OutboxEventEntityMapper::toDomain)

    override fun markDispatched(events: List<OutboxEvent>) {
        if (events.isNotEmpty()) {
            mapper.batchUpdate(events.map(OutboxEventEntityMapper::toEntity))
        }
    }

    override fun reclaimStuck(olderThanSeconds: Int): Int =
        mapper.reclaimStuckClaims(olderThanSeconds)

    override fun unclaimFailed(workerId: String, oeids: List<Long>): Int {
        if (oeids.isEmpty()) return 0
        val params = mapOf(
            "workerId" to workerId,
            "oeids" to oeids
        )
        return mapper.unclaimSpecific(params)
    }

    override fun countNew(): Long = mapper.countByStatus("NEW")
}
