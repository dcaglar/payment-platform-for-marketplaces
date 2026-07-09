package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.edge

import com.dogancaglar.common.db.entity.OutboxEventEntity
import org.apache.ibatis.annotations.Mapper

@Mapper
interface LocalOutboxWriterMapper {
    fun insertOutboxEvent(event: OutboxEventEntity ): Int
    fun insertAllOutboxEvents(events: List<OutboxEventEntity>): Int
    fun checkPendingEventsExist(): Boolean
}
