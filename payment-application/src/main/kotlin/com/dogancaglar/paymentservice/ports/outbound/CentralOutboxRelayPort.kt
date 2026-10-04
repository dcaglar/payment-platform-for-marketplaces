package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import java.time.Instant

interface CentralOutboxRelayPort {
    fun findEligible(tSafe: Instant, batchSize: Int, workerId: String): List<OutboxEvent>
    fun countEligible(tSafe: Instant): Long
    fun markDispatched(oeid: Long, createdAt: Instant)
    fun unclaimSpecific(oeid: Long, createdAt: Instant, workerId: String)

    fun computeTSafe(): Instant?
    fun deleteWatermark(edgeNodeId: String)
    fun reclaimStuck(staleSeconds: Int): Int
}
