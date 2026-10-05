package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.exception.PaymentDomainException
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.require
import com.dogancaglar.paymentservice.domain.model.payment.Payment
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
                PaymentDomainException.InvalidStateTransitionException(
                    "CaptureTx [${this.txId.value}] cannot be transitioned to $newSettleStatus: it has already " +
                        "cleared out of UNMATCHED (current status: ${this.settleStatus})"
                )
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
        // what the PSP settled (the fee and net cash are booked in the settlement journal entry)
        val grossAmount: Amount,
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

        /** The authorization of [payment]'s total amount; authorized, so SUCCESS. */
        fun createAuthTx(txId: TxId, payment: Payment, acquirerReference: String): AuthorizationTx = AuthorizationTx(
            txId = txId,
            paymentId = payment.paymentId,
            paymentIntentId = payment.paymentIntentId,
            acquirerReference = acquirerReference,
            amount = payment.totalAmount,
            status = TxStatus.SUCCESS
        )

        /** A capture of [payment], sent to the PSP and not confirmed yet: PENDING. */
        fun createCaptureTx(
            txId: TxId,
            payment: Payment,
            authorizationTxId: TxId,
            acquirerReference: String,
            amount: Amount
        ): CaptureTx = CaptureTx(
            txId = txId,
            paymentId = payment.paymentId,
            paymentIntentId = payment.paymentIntentId,
            authorizationTxId = authorizationTxId,
            acquirerReference = acquirerReference,
            amount = amount,
            status = TxStatus.PENDING
        )

        /** The PSP's fee on [parentTx]; the payment and the intent are the parent's. */
        fun createPspFeeTx(txId: TxId, parentTx: Tx, amount: Amount): PspFeeTx = PspFeeTx(
            txId = txId,
            paymentId = parentTx.paymentId,
            paymentIntentId = parentTx.paymentIntentId,
            parentTxId = parentTx.txId,
            amount = amount,
            status = TxStatus.SUCCESS
        )

        /** A refund of [captureTx], not confirmed yet: PENDING. The payment and the intent are the capture's. */
        fun createRefundTx(txId: TxId, captureTx: CaptureTx, acquirerReference: String, amount: Amount): RefundTx =
            RefundTx(
                txId = txId,
                paymentId = captureTx.paymentId,
                paymentIntentId = captureTx.paymentIntentId,
                captureTxId = captureTx.txId,
                acquirerReference = acquirerReference,
                amount = amount,
                status = TxStatus.PENDING
            )

        /** Settles [captureTx]: the payment, the intent and the expected amount are the capture's. */
        fun createSettleTx(
            txId: TxId,
            captureTx: CaptureTx,
            acquirerBatchReference: String,
            grossAmount: Amount
        ): SettleTx {
            val originalCaptureAmount = captureTx.amount
            // 🟢 The domain logic stays encapsulated inside the domain module factory boundary
            val derivedStatus = if (grossAmount == originalCaptureAmount) {
                SettleStatus.MATCHED
            } else {
                SettleStatus.DISCREPANCY
            }

            return SettleTx(
                txId = txId,
                paymentId = captureTx.paymentId,
                paymentIntentId = captureTx.paymentIntentId,
                captureTxId = captureTx.txId,
                acquirerBatchReference = acquirerBatchReference,
                grossAmount = grossAmount,
                amount = originalCaptureAmount,
                settleStatus = derivedStatus // Passed directly to the private record constructor
            )
        }

        /** A payout of [payment]'s money, not confirmed yet: PENDING. */
        fun createPayoutTx(
            txId: TxId,
            payment: Payment,
            merchantEntityId: String,
            payoutBatchReference: String,
            amount: Amount
        ): PayoutTx = PayoutTx(
            txId = txId,
            paymentId = payment.paymentId,
            paymentIntentId = payment.paymentIntentId,
            merchantEntityId = merchantEntityId,
            payoutBatchReference = payoutBatchReference,
            amount = amount,
            status = TxStatus.PENDING
        )
    }
}
