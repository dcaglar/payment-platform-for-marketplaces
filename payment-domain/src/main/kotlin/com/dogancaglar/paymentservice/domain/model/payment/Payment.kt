package com.dogancaglar.paymentservice.domain.model.payment

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.ledger.SettleStatus
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import java.time.LocalDateTime

/**
 * Payment — Central DDD Aggregate Root
 *
 * The absolute source of truth for the financial state machine of a single
 * payment within the Mor-DC platform. It enforces all mathematical invariants
 * and state-machine transition rules for the capture/refund lifecycle.
 *
 * ============================================================
 * KEY DESIGN DECISIONS (DO NOT VIOLATE)
 * ============================================================
 *
 * 1. COMPLETE DECOUPLING FROM PaymentIntent:
 *    This aggregate does NOT reference the PaymentIntent class. It is
 *    instantiated from a [PaymentAuthorizedEvent] payload via
 *    [initializeFromAuthEvent], so the domain remains purely fintech-focused
 *    and does not couple Central DB models to Edge Cell models.
 *
 * 2. SPLIT ROUTING MATRIX IS LOCKED AT BIRTH:
 *    The [splits] array is written to the Central DB once — at the moment
 *    [initializeFromAuthEvent] is called — and is thereafter immutable.
 *    This ensures the MARKETPLACE distribution logic in the CAPTURE webhook
 *    path reads a consistent, deterministic routing table.
 *
 * 3. NO GHOST STATES (Golden Rule 4):
 *    The aggregate MUST NOT transition to [PaymentStatus.SENT_FOR_SETTLE]
 *    until [markSentForSettle] is called by CapturePspPerformedConsumer,
 *    which only fires after the outbox acknowledgment of the gateway 202 ACK
 *    has been persisted. No optimistic pre-transition is allowed.
 *
 * 4. MATHEMATICAL SAFETY:
 *    All invariants (capturedAmount ≤ totalAmount, refundedAmount ≤ capturedAmount,
 *    currency consistency) are enforced at both init-time and in each mutation
 *    method, making it impossible to create an inconsistent Payment via any code path.
 *
 * 5. NO E-COMMERCE CONCEPTS:
 *    The aggregate carries no cart items, product lines, or seller-level order IDs.
 *    Financial distribution is expressed solely through the [splits] array.
 *
 * @param paymentId         Snowflake-generated identifier for this Payment record.
 * @param paymentIntentId   Reference to the originating PaymentIntent (for traceability).
 * @param buyerId           Identifier of the purchasing party.
 * @param merchantAccount Primary merchant-of-record entity identifier.
 * @param processingModel   Routing model locked in at authorization time.
 * @param totalAmount       Total authorized amount. Immutable after creation.
 * @param capturedAmount    Running total of confirmed captures. Starts at zero.
 * @param refundedAmount    Running total of confirmed refunds. Starts at zero.
 * @param status            Current lifecycle state of this payment.
 * @param splits            Immutable routing instructions locked in at auth time.
 * @param createdAt         Timestamp of aggregate creation (UTC).
 * @param updatedAt         Timestamp of last state mutation (UTC).
 */
class Payment private constructor(
    val paymentId: PaymentId,
    val paymentIntentId: PaymentIntentId,
    val buyerId: BuyerId,
    val merchantAccount: String,
    val processingModel: ProcessingModel,
    val totalAmount: Amount,
    val capturedAmount: Amount,
    val refundedAmount: Amount,
    val status: PaymentStatus,
    val splits: List<PaymentSplit>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {

    // =========================================================================
    // Aggregate Invariants (enforced on every construction path)
    // =========================================================================

    init {
        require(merchantAccount.isNotBlank()) {
            "merchantAccount must not be blank"
        }
        require(totalAmount.isPositive()) {
            "totalAmount must be positive, but was ${totalAmount.quantity}"
        }
        require(capturedAmount >= Amount.zero(totalAmount.currency)) {
            "capturedAmount cannot be negative, but was ${capturedAmount.quantity}"
        }
        require(refundedAmount >= Amount.zero(totalAmount.currency)) {
            "refundedAmount cannot be negative, but was ${refundedAmount.quantity}"
        }
        require(capturedAmount <= totalAmount) {
            "capturedAmount (${capturedAmount.quantity}) cannot exceed totalAmount (${totalAmount.quantity})"
        }
        require(refundedAmount <= capturedAmount) {
            "refundedAmount (${refundedAmount.quantity}) cannot exceed capturedAmount (${capturedAmount.quantity})"
        }
        require(capturedAmount.currency == totalAmount.currency) {
            "capturedAmount currency (${capturedAmount.currency}) must match totalAmount currency (${totalAmount.currency})"
        }
        require(refundedAmount.currency == totalAmount.currency) {
            "refundedAmount currency (${refundedAmount.currency}) must match totalAmount currency (${totalAmount.currency})"
        }

        // For MARKETPLACE payments, splits must be present and sum to totalAmount.
        if (processingModel == ProcessingModel.MARKETPLACE) {
            require(splits.isNotEmpty()) {
                "MARKETPLACE payment must have at least one PaymentSplit"
            }
            val splitCurrencies = splits.map { it.amount.currency }.distinct()
            require(splitCurrencies.size == 1 && splitCurrencies.first() == totalAmount.currency) {
                "All PaymentSplit amounts must share the same currency as totalAmount"
            }
            val splitSum = splits.sumOf { it.amount.quantity }
            require(splitSum == totalAmount.quantity) {
                "Sum of PaymentSplit amounts ($splitSum) must equal totalAmount (${totalAmount.quantity})"
            }
        }
    }

    // =========================================================================
    // State Machine: CaptureTx Path
    // =========================================================================

    /**
     * markSentForSettle
     *
     * Transitions the aggregate from [PaymentStatus.AUTHORIZED] to
     * [PaymentStatus.SENT_FOR_SETTLE].
     *
     * GOLDEN RULE ENFORCEMENT:
     * This method MUST only be called by CapturePspPerformedConsumer after
     * the OutboxRelayJob has confirmed that an OutboxEvent<ExternalAsyncCaptureToPspPerformed>
     * row was successfully published to Kafka. This guarantees the "No Ghost States"
     * rule: the aggregate's in-flight state is only set after an explicit
     * outbox acknowledgment, never optimistically.
     *
     * @param now  UTC timestamp of the transition (injectable for testing).
     * @return     A new Payment instance with status=SENT_FOR_SETTLE.
     */
    fun markSentForSettle(now: LocalDateTime = Utc.nowLocalDateTime()): Payment {
        require(status == PaymentStatus.AUTHORIZED) {
            "Can only mark SENT_FOR_SETTLE from AUTHORIZED (current=$status)"
        }
        return copy(status = PaymentStatus.SENT_FOR_SETTLE, updatedAt = now)
    }

    /**
     * applyCapture
     *
     * Applies a confirmed capture amount to the aggregate after a successful
     * PSP webhook confirmation. Updates [capturedAmount] and derives the
     * new [PaymentStatus] from the updated totals.
     *
     * Allowed source states: [PaymentStatus.SENT_FOR_SETTLE], [PaymentStatus.PARTIALLY_CAPTURED].
     *
     * @param captureAmount  The amount confirmed by the PSP webhook. Must be positive
     *                       and share the same currency as [totalAmount].
     * @param now            UTC timestamp of the transition.
     * @return               A new Payment instance with updated amounts and status.
     */
    fun applyCapture(
        captureAmount: Amount,
        now: LocalDateTime = Utc.nowLocalDateTime()
    ): Payment {
        require(captureAmount.isPositive()) {
            "captureAmount must be positive, but was ${captureAmount.quantity}"
        }
        require(captureAmount.currency == totalAmount.currency) {
            "captureAmount currency (${captureAmount.currency}) must match totalAmount currency (${totalAmount.currency})"
        }
        require(status in setOf(PaymentStatus.SENT_FOR_SETTLE, PaymentStatus.PARTIALLY_CAPTURED)) {
            "Can only apply capture from SENT_FOR_SETTLE or PARTIALLY_CAPTURED (current=$status)"
        }

        val newCaptured = capturedAmount + captureAmount
        require(newCaptured <= totalAmount) {
            "New capturedAmount ($newCaptured) would exceed totalAmount ($totalAmount)"
        }

        val newStatus = when {
            newCaptured < totalAmount  -> PaymentStatus.PARTIALLY_CAPTURED
            newCaptured == totalAmount -> PaymentStatus.CAPTURED
            else                       -> status // unreachable after guard above
        }

        return copy(capturedAmount = newCaptured, status = newStatus, updatedAt = now)
    }

    /**
     * applySettlement
     *
     * Applies the SDR settlement loopback confirmation to the aggregate.
     * Only allowed from [PaymentStatus.CAPTURED].
     *
     * @param now  UTC timestamp of the transition.
     * @return     A new Payment instance with status=SETTLED.
     */
    fun applySettlement(now: LocalDateTime = Utc.nowLocalDateTime()): Payment {
        //this should not be the only this check,mnore than that  beleiuve
        require(status == PaymentStatus.CAPTURED) {
            "Can only apply settlement to a CAPTURED payment (current=\$status)"
        }
        return copy(status = PaymentStatus.SETTLED, updatedAt = now)
    }

    fun reconcileCaptureSettlement(
        actualGrossAmount: Amount,
        allCaptures: List<Tx.CaptureTx>
    ): ReconciliationResult {
        // Invariant Check 1: Ensure macro lifecycle state allows settlement clearing
        require(status == PaymentStatus.CAPTURED || status == PaymentStatus.PARTIALLY_CAPTURED) {
            "Cannot reconcile settlement against a payment in $status status. Target must be CAPTURED or PARTIALLY_CAPTURED."
        }

        // Domain Rule: Find the outstanding unmatched target line!
        val targetTx = allCaptures.find { it.settleStatus == SettleStatus.UNMATCHED }
            ?: throw IllegalStateException("Outstanding UNMATCHED CaptureTx row not found for target paymentId=${this.paymentId.value}")

        // Invariant Check 2: Evaluate gross volume consistency (Expected vs Actual Cleared)
        val derivedSettleStatus = if (actualGrossAmount == targetTx.amount) {
            SettleStatus.MATCHED
        } else {
            SettleStatus.DISCREPANCY
        }

        // Advance micro transaction state safely using our encapsulated domain method
        val updatedCaptureTx = targetTx.progressReconciliation(derivedSettleStatus)

        // Evaluate macro state machine transition conditions:
        // We check if this newly matched line item completes the puzzle for ALL captures.
        val areAllCapturesMatched = allCaptures.map { if (it.txId == targetTx.txId) updatedCaptureTx else it }
            .all { it.settleStatus == SettleStatus.MATCHED }

        val newStatus = if (areAllCapturesMatched && status == PaymentStatus.CAPTURED) {
            PaymentStatus.SETTLED
        } else {
            status // Remains at CAPTURED or PARTIALLY_CAPTURED to trap any exception/discrepancy lines
        }

        val updatedPayment = this.copy(
            status = newStatus,
            updatedAt = com.dogancaglar.common.time.Utc.nowLocalDateTime()
        )

        return ReconciliationResult(updatedPayment, updatedCaptureTx)
    }

    // Immutable domain DTO returned by the aggregate root
    data class ReconciliationResult(
        val payment: Payment,
        val captureTx: Tx.CaptureTx
    )

    /**
     * voidAuthorization
     *
     * Releases the authorization hold without capturing any funds.
     * Only allowed from [PaymentStatus.AUTHORIZED] (no capture has been sent).
     *
     * @param now  UTC timestamp of the transition.
     * @return     A new Payment instance with status=VOIDED.
     */
    fun voidAuthorization(now: LocalDateTime = Utc.nowLocalDateTime()): Payment {
        require(status == PaymentStatus.AUTHORIZED) {
            "Can only void from AUTHORIZED (current=$status). " +
            "A payment in SENT_FOR_SETTLE or later cannot be voided."
        }
        return copy(status = PaymentStatus.VOIDED, updatedAt = now)
    }

    // =========================================================================
    // State Machine: RefundTx Path
    // =========================================================================

    /**
     * applyRefund
     *
     * Applies a confirmed refund amount to the aggregate after a successful
     * PSP webhook confirmation. Updates [refundedAmount] and derives the
     * new [PaymentStatus] from the updated totals.
     *
     * Allowed source states: [PaymentStatus.CAPTURED], [PaymentStatus.PARTIALLY_CAPTURED],
     * [PaymentStatus.PARTIALLY_REFUNDED].
     *
     * @param refundAmount  The amount confirmed by the PSP webhook. Must be positive,
     *                      same currency, and not cause refundedAmount to exceed capturedAmount.
     * @param now           UTC timestamp of the transition.
     * @return              A new Payment instance with updated amounts and status.
     */
    fun applyRefund(
        refundAmount: Amount,
        now: LocalDateTime = Utc.nowLocalDateTime()
    ): Payment {
        require(refundAmount.isPositive()) {
            "refundAmount must be positive, but was ${refundAmount.quantity}"
        }
        require(refundAmount.currency == totalAmount.currency) {
            "refundAmount currency (${refundAmount.currency}) must match totalAmount currency (${totalAmount.currency})"
        }
        require(capturedAmount > Amount.zero(totalAmount.currency)) {
            "Cannot refund a payment with zero capturedAmount"
        }
        require(status in setOf(
            PaymentStatus.CAPTURED,
            PaymentStatus.PARTIALLY_CAPTURED,
            PaymentStatus.PARTIALLY_REFUNDED
        )) {
            "Can only apply refund from CAPTURED, PARTIALLY_CAPTURED, or PARTIALLY_REFUNDED (current=$status)"
        }

        val newRefunded = refundedAmount + refundAmount
        require(newRefunded <= capturedAmount) {
            "New refundedAmount ($newRefunded) would exceed capturedAmount ($capturedAmount)"
        }

        val newStatus = when {
            newRefunded < capturedAmount  -> PaymentStatus.PARTIALLY_REFUNDED
            newRefunded == capturedAmount -> PaymentStatus.REFUNDED
            else                          -> status // unreachable after guard above
        }

        return copy(refundedAmount = newRefunded, status = newStatus, updatedAt = now)
    }

    // =========================================================================
    // Internal Immutable Copy
    // =========================================================================

    private fun copy(
        capturedAmount: Amount = this.capturedAmount,
        refundedAmount: Amount = this.refundedAmount,
        status: PaymentStatus = this.status,
        updatedAt: LocalDateTime = Utc.nowLocalDateTime()
    ): Payment = Payment(
        paymentId         = paymentId,
        paymentIntentId   = paymentIntentId,
        buyerId           = buyerId,
        merchantAccount = merchantAccount,
        processingModel   = processingModel,
        totalAmount       = totalAmount,
        capturedAmount    = capturedAmount,
        refundedAmount    = refundedAmount,
        status            = status,
        splits            = splits,
        createdAt         = createdAt,
        updatedAt         = updatedAt
    )

    // =========================================================================
    // Display
    // =========================================================================

    override fun toString(): String =
        "Payment(paymentId=${paymentId.value}, paymentIntentId=${paymentIntentId.value}, " +
        "buyerId=${buyerId.value}, merchantAccount=$merchantAccount, " +
        "processingModel=$processingModel, totalAmount=$totalAmount, " +
        "capturedAmount=$capturedAmount, refundedAmount=$refundedAmount, " +
        "status=$status, splits=${splits.size}, createdAt=$createdAt, updatedAt=$updatedAt)"

    // =========================================================================
    // Factory Methods
    // =========================================================================

    companion object {

        /**
         * initializeFromAuthEvent
         *
         * The canonical factory method for creating a Payment aggregate in the
         * Central Core Cluster. Called exclusively by PspResultConsumer when
         * it processes a [PaymentAuthorized] event.
         *
         * This method:
         *  1. Instantiates the aggregate with status=AUTHORIZED.
         *  2. Locks the [splits] routing matrix into the Central DB at the same
         *     time (via the enclosing DB transaction in PspResultConsumer).
         *  3. Sets capturedAmount and refundedAmount to zero.
         *
         * Because the Payment aggregate is decoupled from PaymentIntent, this
         * factory does not reference the PaymentIntent class. All required data
         * is sourced from the [PaymentAuthorized] event payload which was
         * forwarded by the OutboxRelayJob.
         *
         * @param paymentId           Snowflake ID assigned by the Edge Cell.
         * @param paymentIntentId     ID of the originating PaymentIntent.
         * @param buyerId             Purchasing party identifier.
         * @param merchantAccount   Primary MoR merchant entity identifier.
         * @param processingModel     Routing model (DIRECT_MERCHANT or MARKETPLACE).
         * @param totalAmount         Total authorized amount.
         * @param splits              Routing instructions from the PaymentAuthorized event.
         * @param now                 UTC timestamp (injectable for deterministic testing).
         * @return                    A new Payment in AUTHORIZED state.
         */
        fun initializeFromAuthEvent(
            paymentId: PaymentId,
            paymentIntentId: PaymentIntentId,
            buyerId: BuyerId,
            merchantAccount: String,
            processingModel: ProcessingModel,
            totalAmount: Amount,
            splits: List<PaymentSplit>,
            now: LocalDateTime = Utc.nowLocalDateTime()
        ): Payment {
            require(merchantAccount.isNotBlank()) {
                "merchantAccount must not be blank when initializing a Payment"
            }
            return Payment(
                paymentId         = paymentId,
                paymentIntentId   = paymentIntentId,
                buyerId           = buyerId,
                merchantAccount = merchantAccount,
                processingModel   = processingModel,
                totalAmount       = totalAmount,
                capturedAmount    = Amount.zero(totalAmount.currency),
                refundedAmount    = Amount.zero(totalAmount.currency),
                status            = PaymentStatus.AUTHORIZED,
                splits            = splits,
                createdAt         = now,
                updatedAt         = now
            )
        }

        /**
         * rehydrate
         *
         * Reconstructs a Payment aggregate from persisted database columns.
         * Used exclusively by the repository layer (e.g., PaymentRepository MyBatis mapper).
         * No business logic runs here — all validation runs in init{}.
         */
        fun rehydrate(
            paymentId: PaymentId,
            paymentIntentId: PaymentIntentId,
            buyerId: BuyerId,
            merchantAccount: String,
            processingModel: ProcessingModel,
            totalAmount: Amount,
            capturedAmount: Amount,
            refundedAmount: Amount,
            status: PaymentStatus,
            splits: List<PaymentSplit>,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime
        ): Payment = Payment(
            paymentId         = paymentId,
            paymentIntentId   = paymentIntentId,
            buyerId           = buyerId,
            merchantAccount = merchantAccount,
            processingModel   = processingModel,
            totalAmount       = totalAmount,
            capturedAmount    = capturedAmount,
            refundedAmount    = refundedAmount,
            status            = status,
            splits            = splits,
            createdAt         = createdAt,
            updatedAt         = updatedAt
        )
    }
}
