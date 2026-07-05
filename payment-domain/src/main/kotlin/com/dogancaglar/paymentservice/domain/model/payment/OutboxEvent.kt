package com.dogancaglar.paymentservice.domain.model.payment

import com.dogancaglar.common.time.Utc
import java.time.LocalDateTime

/**
 * Represents a durable outbox event entry.
 * Created atomically with domain changes to ensure reliable async publication.
 */class OutboxEvent private constructor(
    val oeid: Long,
    val  partitionKey: String,
    val eventType: String,
    val aggregateId: String,
    val traceId: String,
    val eventId: String,
    val parentEventId: String?,

    val payload: String,
    val status: Status,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {

    /** Domain-safe transitions */
    fun markAsProcessing(): OutboxEvent {
        require(status == Status.NEW) {
            "Invalid transition from $status to ${Status.PROCESSING}"
        }
        return copy(status = Status.PROCESSING)
    }

    fun markAsSent(): OutboxEvent {
        require(status == Status.NEW || status == Status.PROCESSING) {
            "Invalid transition from $status to ${Status.SENT}"
        }
        return copy(status = Status.SENT)
    }

    /** Functional immutability */
    private fun copy(
        status: Status = this.status,
        updatedAt: LocalDateTime = Utc.nowLocalDateTime()
    ): OutboxEvent = OutboxEvent(
        oeid = oeid,
        eventType = eventType,
        partitionKey = partitionKey,
        aggregateId = aggregateId,
        traceId = traceId,
        eventId = eventId,
        parentEventId = parentEventId,
        payload = payload,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    /** Marker enum for outbox lifecycle */
    enum class Status { NEW, PROCESSING, SENT }

    override fun toString(): String {
        return "OutboxEvent(oeid=$oeid, eventType='$eventType', aggregateId='$aggregateId', '$aggregateId' payload='$payload', status=$status, createdAt=$createdAt, updatedAt=$updatedAt)"
    }

    companion object {

        /** 🔹 Create brand new event for persistence */
        fun createNew(
            oeid: Long,
            partitionKey: String,
            eventType: String,
            aggregateId: String,
            traceId: String,
            eventId:String,
            parentEventId: String?,
            payload: String
        ): OutboxEvent {
            val now = Utc.nowLocalDateTime()
           return  OutboxEvent(
                oeid = oeid,
                partitionKey = partitionKey,
                eventType = eventType,
                aggregateId = aggregateId,
               traceId = traceId,
               eventId=eventId,
               parentEventId=parentEventId,
                payload = payload,
                status = Status.NEW,
                createdAt = now,
                updatedAt = now
            )
        }

        /** 🔹 Rehydrate from persistence row */
        fun rehydrate(
            oeid: Long,
            partitionKey: String,
            eventType: String,
            aggregateId: String,
            traceId: String,
            eventId: String,
            parentEventId: String?,
            payload: String,
            status: String,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime
        ): OutboxEvent = OutboxEvent(
            oeid = oeid,
            partitionKey = partitionKey,
            eventType = eventType,
            aggregateId = aggregateId,
            traceId = traceId,
            eventId = eventId,
            parentEventId = parentEventId,
            payload = payload,
            status = Status.valueOf(status.uppercase()), // safe parse
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}