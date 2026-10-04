package com.dogancaglar.common.db.entity

import java.time.LocalDateTime

data class TransferEntity(
    val transferId: Long,
    val paymentId: Long,
    val paymentIntentId: Long,
    val merchantAccount: String,
    val amountValue: Long,
    val currency: String,
    val sourceAccount: String,
    val targetAccount: String,
    val transferType: String,
    val status: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
