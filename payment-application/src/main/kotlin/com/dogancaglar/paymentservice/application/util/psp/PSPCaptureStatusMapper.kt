package com.dogancaglar.paymentservice.application.util.psp

import com.dogancaglar.paymentservice.domain.model.payment.PspModificationStatus

/**
 * Maps PSP capture (seller-level) responses to internal PaymentOrderStatus.
 * Used by PaymentOrderPspCallExecutor and PaymentOrderPspResultApplier.
 */
object PSPCaptureStatusMapper {

    fun fromPspCaptureResponseCode(code: String): PspModificationStatus = when (code.uppercase()) {
        // Final successful state
        "CAPTURE_SUCCESS", "SUCCESSFUL_FINAL" -> PspModificationStatus.CAPTURED

        // Final non-retryable failure states
        "CAPTURE_FAILED_FINAL", "CAPTURE_DECLINED_FINAL" -> PspModificationStatus.CAPTURE_FAILED

        // Transient (retryable) errors
        "TRANSIENT_NETWORK_ERROR",
        "PENDING_CAPTURE",
        "TIMEOUT_EXCEEDED_1S_TRANSIENT" -> PspModificationStatus.PENDING_CAPTURE // will be retried

        else -> PspModificationStatus.CAPTURE_FAILED
    }

    fun requiresRetry(status: PspModificationStatus): Boolean {
        return status in retryableStatuses
    }

    private val retryableStatuses = setOf(
        PspModificationStatus.PENDING_CAPTURE // transient retry bucket
    )
}
