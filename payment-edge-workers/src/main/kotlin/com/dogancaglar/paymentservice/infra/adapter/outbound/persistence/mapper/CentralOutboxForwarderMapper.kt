package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.common.db.entity.EdgeWatermarkEntity
import com.dogancaglar.common.db.entity.OutboxEventEntity
import org.apache.ibatis.annotations.Mapper

@Mapper
interface CentralOutboxForwarderMapper {
    fun insertBatch(entries: List<OutboxEventEntity>)

    // Edge Watermark operations
    fun isSchemaReady(): Boolean
    fun upsert(entity: EdgeWatermarkEntity)
    fun deleteWatermark(edgeNodeId: String)
}
