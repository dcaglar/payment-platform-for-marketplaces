package com.dogancaglar.paymentservice.domain.model.payment

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.exception.InternalTransferDomainException
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.require
import com.dogancaglar.paymentservice.domain.model.vo.InternalTransferId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import java.time.LocalDateTime

class InternalTransfer private constructor(
    val transferId: InternalTransferId,
    val paymentId: PaymentId,
    val paymentIntentId: PaymentIntentId,
    val merchantAccount: String,
    val amount: Amount,
    val targetAccount: String,
    val sourceAccount: String,
    val transferType: String,
    val status: InternalTransferStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {

    // =========================================================================
    // Aggregate Invariants (enforced on every construction path)
    // =========================================================================

    init {
        require(targetAccount.isNotBlank()) {
            InternalTransferDomainException.InvariantViolationException(
                "transferId=${transferId.value}: targetAccount must not be blank"
            )
        }
        require(sourceAccount.isNotBlank()) {
            InternalTransferDomainException.InvariantViolationException(
                "transferId=${transferId.value}: sourceAccount must not be blank"
            )
        }
        require(amount.isPositive()) {
            InternalTransferDomainException.InvariantViolationException(
                "transferId=${transferId.value}: amount must be positive, but was ${amount.quantity}"
            )
        }
        require(paymentIntentId.value > 0) {
            InternalTransferDomainException.InvariantViolationException(
                "transferId=${transferId.value}: paymentIntentId must not be zero or negative"
            )
        }
        require(paymentId.value > 0) {
            InternalTransferDomainException.InvariantViolationException(
                "transferId=${transferId.value}: paymentId must not be zero or negative"
            )
        }
    }

    // =========================================================================
    // State Machine
    // =========================================================================

    // =========================================================================
    // Internal Immutable Copy
    // =========================================================================

    fun markSentForTransfer(now: LocalDateTime = Utc.nowLocalDateTime()): InternalTransfer {
        require(status == InternalTransferStatus.CREATED_PENDING) {
            InternalTransferDomainException.InvalidStateTransitionException(
                "transferId=${transferId.value}: can only mark SENT_FOR_TRANSFER from CREATED_PENDING (current=$status)"
            )
        }
        return copy(status = InternalTransferStatus.SENT_FOR_TRANSFER, updatedAt = now)
    }

    fun markTransferred(now: LocalDateTime = Utc.nowLocalDateTime()): InternalTransfer {
        require(status == InternalTransferStatus.SENT_FOR_TRANSFER) {
            InternalTransferDomainException.InvalidStateTransitionException(
                "transferId=${transferId.value}: can only mark TRANSFERRED from SENT_FOR_TRANSFER (current=$status)"
            )
        }
        return copy(status = InternalTransferStatus.TRANSFERRED, updatedAt = now)
    }

    private fun copy(
        status: InternalTransferStatus = this.status,
        updatedAt: LocalDateTime = Utc.nowLocalDateTime()
    ): InternalTransfer = InternalTransfer(
        transferId = transferId,
        paymentIntentId = paymentIntentId,
        merchantAccount = merchantAccount,
        paymentId = paymentId,
        amount = amount,
        targetAccount = targetAccount,
        sourceAccount = sourceAccount,
        transferType = transferType,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    // =========================================================================
    // Display
    // =========================================================================

    override fun toString(): String =
        "amount=$amount, target=$targetAccount/$targetAccount, " +
            "source=$sourceAccount/$sourceAccount, status=$status, transferType= $transferType createdAt=$createdAt, " +
            "updatedAt=$updatedAt)"

    // =========================================================================
    // Factory Methods
    // =========================================================================

    companion object {

        fun createNew(
            transferId: InternalTransferId,
            paymentIntentId: PaymentIntentId,
            paymentId: PaymentId,
            merchantAccount: String,
            amount: Amount,
            sourceAccount: String,
            targetAccount: String,
            transferType: String,
            now: LocalDateTime = Utc.nowLocalDateTime()
        ): InternalTransfer {
            return InternalTransfer(
                transferId = transferId,
                paymentId = paymentId,
                paymentIntentId = paymentIntentId,
                merchantAccount = merchantAccount,
                amount = amount,
                targetAccount = targetAccount,
                sourceAccount = sourceAccount,
                transferType = transferType,
                status = InternalTransferStatus.CREATED_PENDING,
                createdAt = now,
                updatedAt = now
            )
        }

        fun rehydrate(
            transferId: InternalTransferId,
            paymentId: PaymentId,
            paymentIntentId: PaymentIntentId,
            merchantAccount: String,
            amount: Amount,
            targetAccount: String,
            sourceAccount: String,
            status: InternalTransferStatus,
            transferType: String,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime
        ): InternalTransfer = InternalTransfer(
            transferId = transferId,
            paymentIntentId = paymentIntentId,
            paymentId = paymentId,
            merchantAccount = merchantAccount,
            amount = amount,
            targetAccount = targetAccount,
            sourceAccount = sourceAccount,
            transferType = transferType,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
