package com.dogancaglar.common.db.entity

import java.time.Instant

/**
 * PaymentTxEntity
 *
 * Infrastructure POJO — the flat, column-by-column representation of a
 * Tx record as stored in the 'payment_transactions' table.
 *
 * Design rules (strictly enforced):
 *  - NO domain types. All IDs are raw Long primitives here.
 *  - NO business logic, no state machine transitions.
 *  - All enum values (txType, status, settleStatus) are stored as String
 *    so the DB schema is decoupled from domain enum refactors.
 *  - Conversion to/from the domain [Tx] sealed hierarchy happens
 *    exclusively in [PaymentTxAdapter].
 *
 * Column mapping:
 *  txId              → payment_transactions.tx_id          (Snowflake Long, PK)
 *  txType            → payment_transactions.tx_type        ("AUTHORIZATION"|"CAPTURE"|"REFUND"|"SETTLE")
 *  paymentId         → payment_transactions.payment_id     (FK to payments)
 *  parentTxId        → payment_transactions.parent_tx_id   (nullable; FK to parent Tx)
 *  acquirerReference → payment_transactions.acquirer_ref   (PSP / acquirer reference string)
 *  amountValue       → payment_transactions.amount_value   (smallest unit, e.g. cents)
 *  amountCurrency    → payment_transactions.amount_currency (ISO 4217)
 *  status            → payment_transactions.status         ("PENDING"|"SUCCESS"|"FAILED")
 *  settleStatus      → payment_transactions.settle_status  (nullable; "UNMATCHED"|"MATCHED"|"DISCREPANCY")
 *  acquirerBatchRef  → payment_transactions.acquirer_batch_ref (nullable; for SETTLE rows only)
 *  settledAmountValue→ payment_transactions.settled_amount_value (nullable; for SETTLE rows only)
 *  createdAt         → payment_transactions.created_at
 */
data class PaymentTxEntity(
    val txId: Long,
    val txType: String,
    val paymentId: Long,
    val paymentIntentId: Long,
    val parentTxId: Long?,
    val acquirerReference: String,
    val amountValue: Long,
    val amountCurrency: String,
    val status: String, // TxStatus.name
    val settleStatus: String?, // SettleStatus.name — null for non-CAPTURE rows
    val acquirerBatchRef: String?, // populated only for SETTLE rows
    val settledAmountValue: Long?, // populated only for SETTLE rows
    val createdAt: Instant? = null
)
