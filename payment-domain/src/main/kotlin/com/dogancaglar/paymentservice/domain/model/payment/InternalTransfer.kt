package com.dogancaglar.paymentservice.domain.model.payment

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import com.dogancaglar.paymentservice.domain.model.vo.InternalTransferId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import java.time.LocalDateTime

class InternalTransfer private constructor(
    val transferId: InternalTransferId,
    val sourceTransactionId: TxId,
    val paymentId: PaymentId,
    val paymentIntentId: PaymentIntentId,
    val merchantAccountId: String,
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
            "targetEntityId must not be blank"
        }
        require(sourceAccount.isNotBlank()) {
            "sourceEntityId must not be blank"
        }
        require(amount.isPositive()) {
            "amount must be positive, but was ${amount.quantity}"
        }
        require(paymentIntentId.value >0) {
            "paymentIntentId must not be zero or negative"
        }
        require(paymentId.value>0) {
            "paymentId must not be zero or negative"
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
            "Can only mark SENT_FOR_TRANSFER from CREATED_PENDING (current=$status)"
        }
        return copy(status = InternalTransferStatus.SENT_FOR_TRANSFER, updatedAt = now)
    }

    fun markTransferred(now: LocalDateTime = Utc.nowLocalDateTime()): InternalTransfer {
        require(status == InternalTransferStatus.SENT_FOR_TRANSFER) {
            "Can only mark TRANSFERRED from SENT_FOR_TRANSFER (current=$status)"
        }
        return copy(status = InternalTransferStatus.TRANSFERRED, updatedAt = now)
    }

    private fun copy(
        status: InternalTransferStatus = this.status,
        updatedAt: LocalDateTime = Utc.nowLocalDateTime()
    ): InternalTransfer = InternalTransfer(
        transferId          = transferId,
        sourceTransactionId = sourceTransactionId,
        paymentIntentId = paymentIntentId,
        merchantAccountId = merchantAccountId,
        paymentId = paymentId,
        amount              = amount,
        targetAccount      = targetAccount,
        sourceAccount      = sourceAccount,
        transferType        = transferType,
        status              = status,
        createdAt           = createdAt,
        updatedAt           = updatedAt
    )

    // =========================================================================
    // Display
    // =========================================================================

    override fun toString(): String =
        "InternalTransfer(transferId=${transferId.value}, sourceTransactionId=${sourceTransactionId.value}, " +
        "amount=$amount, target=$targetAccount/$targetAccount, " +
        "source=$sourceAccount/$sourceAccount, status=$status, transferType= $transferType createdAt=$createdAt, updatedAt=$updatedAt)"

    // =========================================================================
    // Factory Methods
    // =========================================================================

    companion object {

        fun createNew(
            transferId: InternalTransferId,
            sourceTransactionId: TxId,
            paymentIntentId: PaymentIntentId,
            paymentId: PaymentId,
            merchantAccountId: String,
            amount: Amount,
            sourceAccount: String,
            targetAccount: String,
            transferType :String,
            now: LocalDateTime = Utc.nowLocalDateTime()
        ): InternalTransfer {
            return InternalTransfer(
                transferId          = transferId,
                sourceTransactionId = sourceTransactionId,
                paymentId = paymentId,
                paymentIntentId = paymentIntentId,
                merchantAccountId = merchantAccountId,
                amount              = amount,
                targetAccount      = targetAccount,
                sourceAccount      = sourceAccount,
                transferType = transferType,
                status              = InternalTransferStatus.CREATED_PENDING,
                createdAt           = now,
                updatedAt           = now
            )
        }

        fun rehydrate(
            transferId: InternalTransferId,
            sourceTransactionId: TxId,
            paymentId: PaymentId,
            paymentIntentId: PaymentIntentId,
            merchantAccountId: String,
            amount: Amount,
            targetAccount: String,
            sourceAccount: String,
            status: InternalTransferStatus,
            transferType : String,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime
        ): InternalTransfer = InternalTransfer(
            transferId          = transferId,
            sourceTransactionId = sourceTransactionId,
            paymentIntentId = paymentIntentId,
            paymentId = paymentId,
            merchantAccountId = merchantAccountId,
            amount              = amount,
            targetAccount      = targetAccount,
            sourceAccount      = sourceAccount,
            transferType = transferType,
            status              = status,
            createdAt           = createdAt,
            updatedAt           = updatedAt
        )
    }
}




