package com.dogancaglar.paymentservice.ports.outbound

import java.util.UUID

interface IdempotencyStorePort {
    fun tryInsertPending(key: UUID, requestHash: String): Boolean
    fun findByKey(key: UUID): IdempotencyRecord?
    fun updatePaymentIntentId(key: UUID, paymentIntentId: Long)
    fun updateResponsePayload(key: UUID, payload: String, paymentIntentId: Long)
    fun deletePending(key: UUID)
}
