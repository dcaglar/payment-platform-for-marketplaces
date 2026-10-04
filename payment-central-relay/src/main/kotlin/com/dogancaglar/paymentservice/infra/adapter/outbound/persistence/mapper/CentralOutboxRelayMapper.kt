package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.common.db.entity.OutboxEventEntity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.Instant

@Mapper
interface CentralOutboxRelayMapper {
    fun findEligible(
        @Param("tSafe") tSafe: Instant,
        @Param("batchSize") batchSize: Int,
        @Param("workerId") workerId: String
    ): List<OutboxEventEntity>
    fun countEligible(tSafe: Instant): Long
    fun markDispatched(@Param("oeid") oeid: Long, @Param("createdAt") createdAt: Instant)

    // 2. ADDED unclaimSpecific
    fun unclaimSpecific(
        @Param("oeid") oeid: Long,
        @Param("createdAt") createdAt: Instant,
        @Param("workerId") workerId: String
    )
    fun reclaimStuckClaims(@Param("olderThanSeconds") olderThanSeconds: Int): Int

    // Edge Watermark operations (consolidated into outbox mapper)
    fun computeTSafe(): Instant?
    fun deleteWatermark(edgeNodeId: String)
}
