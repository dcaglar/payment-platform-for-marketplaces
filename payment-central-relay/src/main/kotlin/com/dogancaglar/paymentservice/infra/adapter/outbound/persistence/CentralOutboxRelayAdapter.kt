package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.common.db.converter.OutboxEventEntityMapper
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.ports.outbound.CentralOutboxRelayPort
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.CentralOutboxRelayMapper
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class CentralOutboxRelayAdapter(
    private val mapper: CentralOutboxRelayMapper
) : CentralOutboxRelayPort {



    override fun findEligible(tSafe: Instant, batchSize: Int, workerId: String): List<OutboxEvent> {
        val entities = mapper.findEligible(tSafe, batchSize, workerId)
        return entities.map {
            OutboxEventEntityMapper.toDomain(it)
        }
    }

    override fun markDispatched(oeid: Long, createdAt: Instant) {
        mapper.markDispatched(oeid, createdAt)
    }

    override fun countEligible(tSafe: Instant): Long {
        return mapper.countEligible(tSafe)
    }

    // 2. ADDED implementation for unclaimSpecific
    override fun unclaimSpecific(oeid: Long, createdAt: Instant,workerId: String) {
        mapper.unclaimSpecific(oeid, createdAt,workerId)
    }

    override fun computeTSafe(): Instant? {
        return mapper.computeTSafe()
    }

    override fun deleteWatermark(edgeNodeId: String) {
        mapper.deleteWatermark(edgeNodeId)
    }

    override fun reclaimStuck(staleSeconds: Int): Int {
        return mapper.reclaimStuckClaims(staleSeconds)
    }
}
