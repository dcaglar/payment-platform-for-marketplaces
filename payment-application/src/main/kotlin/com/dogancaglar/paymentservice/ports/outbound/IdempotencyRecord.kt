// payment-application/src/main/kotlin/.../ports/outbound/IdempotencyStorePort.kt
package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.common.time.Utc
import java.time.Instant
import java.util.UUID

data class IdempotencyRecord(
    val id: Long? = null,
    val idempotencyKey: UUID,
    val requestHash: String,
    val paymentIntentId: Long? = null,
    val responsePayload: String? = null,
    val status: InitialRequestStatus = InitialRequestStatus.PENDING,
    val createdAt: Instant = Utc.nowInstant()
)

// this tell if the intial request is pending or completed already
enum class InitialRequestStatus {
    PENDING,
    COMPLETED
}
