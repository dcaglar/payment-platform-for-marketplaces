package com.dogancaglar.common.db.entity

import java.time.Instant

data class OutboxEventEntity(
    val oeid: Long,
    val partitionKey: String,
    val eventType: String,
    val aggregateId: String,
    val eventId:String,
    val parentEventId: String?,
    val payload: String,
    var status: String = "NEW",
    val createdAt: Instant,
    val updatedAt: Instant,
    val claimedAt: Instant? = null,
    val claimedBy: String? = null
) {
    fun markAsSent() {
        this.status = "SENT"
    }
}