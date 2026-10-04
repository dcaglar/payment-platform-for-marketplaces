package com.dogancaglar.paymentservice.domain.model.payment

// status of PspModification(captures)
enum class PspModificationStatus {
    // Initial state upon creation
    CAPTURE_RECEIVED,

    // probably set this in Enqueuer
    CAPTURE_REQUESTED,

    // if psp call returned declined,set final response
    CAPTURE_FAILED,

    // if psp call hypotehtically approved then update to this
    CAPTURED, // final respinse
    REFUND_REQUESTED,
    REFUND_RECEIVED,
    REFUND_FAILED,
    REFUND_DECLINED_FINAL,
    REFUNDED,

    // RETRYABLE FAILURES
    PENDING_CAPTURE,
    PENDING_REFUND,
    TIMEOUT_EXCEEDED_1S_TRANSIENT, // PSP call timeout
    PSP_UNAVAILABLE_TRANSIENT;

    fun isExternalCapturePspResponse(): Boolean =
        isRetryablePspResponse() || isTerminalPspResponse()

    fun isTerminalPspResponse(): Boolean =
        this in setOf(CAPTURE_FAILED, CAPTURED)

    fun isRetryablePspResponse(): Boolean =
        this in setOf(PENDING_CAPTURE, TIMEOUT_EXCEEDED_1S_TRANSIENT, PSP_UNAVAILABLE_TRANSIENT)

    fun requiresRetry(): Boolean = isRetryablePspResponse()
}

/**
 * Lightweight domain response representing the tracking token and
 * modification status received from the external network gateway handshake.
 */
data class PspCaptureGatewayResponse(
    val pspReference: String,
    val status: PspModificationStatus
)
