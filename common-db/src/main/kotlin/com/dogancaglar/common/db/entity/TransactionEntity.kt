package com.dogancaglar.common.db.entity

import java.time.Instant

/** One row of `transactions`. */
data class TransactionEntity(
    val paymentId: Long,
    val paymentIntentId: Long,
    val publicPaymentIntentId: String,
    val merchantAccount: String,
    val buyerId: String,
    val orderId: String,
    val pspReference: String,
    val processingModel: String,
    val totalAmount: Long,
    val currency: String,
    val authorizedAt: Instant,
    val capturedAt: Instant?,
    val settledAt: Instant?,
    val cardBrand: String?,
    val cardLast4: String?
)

/** One row of `transaction_splits`. */
data class TransactionSplitEntity(
    val paymentId: Long,
    val lineNo: Int,
    val accountType: String,
    val account: String,
    val amount: Long,
    val currency: String
)
