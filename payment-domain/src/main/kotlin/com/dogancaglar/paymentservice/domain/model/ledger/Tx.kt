package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import java.time.Instant

/**
 * Tx
 *
 * Sealed hierarchy representing every financial transaction record persisted in the Central DB.
 * Each subclass maps to a discrete gateway event, internal ledger adjustment, or payout disbursement.
 *
 * Design constraints (Golden Rules):
 *  - These are PURE DATA RECORDS. No domain logic, no side effects.
 *  - All IDs use the canonical typed value wrappers (PaymentId, TxId, PaymentIntentId).
 *  - [status] uses the [TxStatus] enum so that all code paths handle PENDING, SUCCESS, and FAILED explicitly.
 *  - [acquirerReference] is non-nullable on terminal gateway transactions.
 */
sealed class Tx {

    abstract val txId: TxId
    abstract val txType: JournalType
    abstract val paymentId: PaymentId
    abstract val paymentIntentId: PaymentIntentId
    abstract val status: TxStatus
    abstract val amount: Amount
    abstract val createdAt: Instant

    // -------------------------------------------------------------------------
    // 1. AuthorizationTx — Card Network Hold Placement
    // -------------------------------------------------------------------------
    data class AuthorizationTx(
        override val txId: TxId,
        override val paymentId: PaymentId,
        override val paymentIntentId: PaymentIntentId,
        val acquirerReference: String,
        override val amount: Amount,
        override val status: TxStatus = TxStatus.SUCCESS,
        override val createdAt: Instant = Instant.now()
    ) : Tx() {
        override val txType = JournalType.AUTHORIZATION
    }

    // -------------------------------------------------------------------------
    // 2. CaptureTx — Gross Collection Trigger From Gateway
    // -------------------------------------------------------------------------
    data class CaptureTx(
        override val txId: TxId,
        override val paymentId: PaymentId,
        override val paymentIntentId: PaymentIntentId,
        val authorizationTxId: TxId,
        val acquirerReference: String,
        override val amount: Amount,
        override val status: TxStatus = TxStatus.PENDING,
        val settleStatus: SettleStatus = SettleStatus.UNMATCHED,
        override val createdAt: Instant = Instant.now()
    ) : Tx() {
        override val txType = JournalType.CAPTURE

        /**
         * Progresses the settlement state of an outstanding capture record.
         */
        fun progressReconciliation(newSettleStatus: SettleStatus): CaptureTx {
            // Invariant Check: Prevent double-clearing or regression mutations
            require(this.settleStatus == SettleStatus.UNMATCHED) {
                "Ledger Security Invariant Violation: CaptureTx [${this.txId.value}] cannot be transitioned " +
                        "to $newSettleStatus because it has already cleared out of UNMATCHED (Current status: ${this.settleStatus})"
            }

            return this.copy(settleStatus = newSettleStatus)
        }
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // 4. PspFeeTx — Explicit Processing Cost Assessments
    // -------------------------------------------------------------------------
    data class PspFeeTx(
        override val txId: TxId,
        override val paymentId: PaymentId,
        override val paymentIntentId: PaymentIntentId,
        val parentTxId: TxId, // Can point to a CaptureTx or a SettleTx batch entry
        override val amount: Amount,
        override val status: TxStatus = TxStatus.SUCCESS,
        override val createdAt: Instant = Instant.now()
    ) : Tx() {
        override val txType = JournalType.PSP_FEE
    }

    // -------------------------------------------------------------------------
    // 5. RefundTx — Transaction Reversal
    // -------------------------------------------------------------------------
    data class RefundTx(
        override val txId: TxId,
        override val paymentId: PaymentId,
        override val paymentIntentId: PaymentIntentId,
        val captureTxId: TxId,
        val acquirerReference: String,
        override val amount: Amount,
        override val status: TxStatus = TxStatus.PENDING,
        override val createdAt: Instant = Instant.now()
    ) : Tx() {
        override val txType = JournalType.REFUND
    }

    // -------------------------------------------------------------------------
    // 6. SettleTx — Reconciliation of Incoming Cash Batches From Acquirer
    // -------------------------------------------------------------------------
    data class SettleTx(
        override val txId: TxId,
        override val paymentId: PaymentId,
        override val paymentIntentId: PaymentIntentId,
        val captureTxId: TxId,
        val acquirerBatchReference: String,
        val grossAmount: Amount,
        val feeAmount: Amount,
        val netCashAmount: Amount,
        override val amount: Amount, // Original expected capture volume
        val settleStatus: SettleStatus, // 🟢 Explicit, immutable domain property
        override val status: TxStatus = TxStatus.SUCCESS,
        override val createdAt: Instant = Instant.now()
    ) : Tx() {
        override val txType = JournalType.SETTLEMENT
    }

    // -------------------------------------------------------------------------
    // 7. PayoutTx — Outbound Bank Disbursals to Verified Sellers
    // -------------------------------------------------------------------------
    data class PayoutTx(
        override val txId: TxId,
        override val paymentId: PaymentId,
        override val paymentIntentId: PaymentIntentId,
        val merchantEntityId: String,
        val payoutBatchReference: String,
        override val amount: Amount,
        override val status: TxStatus = TxStatus.PENDING,
        override val createdAt: Instant = Instant.now()
    ) : Tx() {
        override val txType = JournalType.PAYOUT
    }

    // -------------------------------------------------------------------------
    // Companion Object Clean Factory Methods
    // -------------------------------------------------------------------------
    companion object {

        fun createAuthTx(
            txId: TxId,
            paymentId: PaymentId,
            paymentIntentId: PaymentIntentId,
            acquirerReference: String,
            amount: Amount,
            status: TxStatus = TxStatus.SUCCESS
        ): AuthorizationTx = AuthorizationTx(
            txId = txId,
            paymentId = paymentId,
            paymentIntentId = paymentIntentId,
            acquirerReference = acquirerReference,
            amount = amount,
            status = status
        )

        fun createCaptureTx(
            txId: TxId,
            paymentId: PaymentId,
            paymentIntentId: PaymentIntentId,
            authorizationTxId: TxId,
            acquirerReference: String,
            amount: Amount,
            status: TxStatus = TxStatus.PENDING
        ): CaptureTx = CaptureTx(
            txId = txId,
            paymentId = paymentId,
            paymentIntentId = paymentIntentId,
            authorizationTxId = authorizationTxId,
            acquirerReference = acquirerReference,
            amount = amount,
            status = status
        )

        fun createPspFeeTx(
            txId: TxId,
            paymentId: PaymentId,
            paymentIntentId: PaymentIntentId,
            parentTxId: TxId,
            amount: Amount,
            status: TxStatus = TxStatus.SUCCESS
        ): PspFeeTx = PspFeeTx(
            txId = txId,
            paymentId = paymentId,
            paymentIntentId = paymentIntentId,
            parentTxId = parentTxId,
            amount = amount,
            status = status
        )

        fun createRefundTx(
            txId: TxId,
            paymentId: PaymentId,
            paymentIntentId: PaymentIntentId,
            captureTxId: TxId,
            acquirerReference: String,
            amount: Amount,
            status: TxStatus = TxStatus.PENDING
        ): RefundTx = RefundTx(
            txId = txId,
            paymentId = paymentId,
            paymentIntentId = paymentIntentId,
            captureTxId = captureTxId,
            acquirerReference = acquirerReference,
            amount = amount,
            status = status
        )

        fun createSettleTx(
            txId: TxId,
            paymentId: PaymentId,
            paymentIntentId: PaymentIntentId,
            captureTxId: TxId,
            acquirerBatchReference: String,
            grossAmount: Amount,
            feeAmount: Amount,
            netCashAmount: Amount,
            originalCaptureAmount: Amount
        ): SettleTx {
            // 🟢 The domain logic stays encapsulated inside the domain module factory boundary
            val derivedStatus = if (grossAmount == originalCaptureAmount) SettleStatus.MATCHED else SettleStatus.DISCREPANCY

            return SettleTx(
                txId = txId,
                paymentId = paymentId,
                paymentIntentId = paymentIntentId,
                captureTxId = captureTxId,
                acquirerBatchReference = acquirerBatchReference,
                grossAmount = grossAmount,
                feeAmount = feeAmount,
                netCashAmount = netCashAmount,
                amount = originalCaptureAmount,
                settleStatus = derivedStatus // Passed directly to the private record constructor
            )
        }

        fun createPayoutTx(
            txId: TxId,
            paymentId: PaymentId,
            paymentIntentId: PaymentIntentId,
            merchantEntityId: String,
            payoutBatchReference: String,
            amount: Amount,
            status: TxStatus = TxStatus.PENDING
        ): PayoutTx = PayoutTx(
            txId = txId,
            paymentId = paymentId,
            paymentIntentId = paymentIntentId,
            merchantEntityId = merchantEntityId,
            payoutBatchReference = payoutBatchReference,
            amount = amount,
            status = status
        )
    }
}


