package com.dogancaglar.common.db.converter

import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.common.db.entity.OutboxEventEntity
import java.time.ZoneOffset

object OutboxEventEntityMapper {

    fun toDomain(entity: OutboxEventEntity): OutboxEvent =
        OutboxEvent.rehydrate(
            oeid = entity.oeid,
            partitionKey = entity.partitionKey,
            eventType = entity.eventType,
            aggregateId = entity.aggregateId,
            eventId = entity.eventId,
            parentEventId = entity.parentEventId,
            payload = entity.payload,
            status = entity.status,
            createdAt = entity.createdAt.atOffset(ZoneOffset.UTC).toLocalDateTime(),
            updatedAt = entity.updatedAt.atOffset(ZoneOffset.UTC).toLocalDateTime()
        )

    fun toEntity(domain: OutboxEvent): OutboxEventEntity =
        OutboxEventEntity(
            oeid = domain.oeid,
            partitionKey = domain.partitionKey,
            eventType = domain.eventType,
            aggregateId = domain.aggregateId,
            eventId = domain.eventId,
            parentEventId = domain.parentEventId,
            payload = domain.payload,
            status = domain.status.name,
            createdAt = domain.createdAt.toInstant(ZoneOffset.UTC),
            updatedAt = domain.updatedAt.toInstant(ZoneOffset.UTC),
            claimedAt = null,    // infra-managed
            claimedBy = null     // infra-managed
        )
}