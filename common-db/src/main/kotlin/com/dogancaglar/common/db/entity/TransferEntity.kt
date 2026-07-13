package com.dogancaglar.common.db.entity

import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import java.time.LocalDateTime

data class TransferEntity(
    val transferId: Long,
    val paymentId  : Long,
    val paymentIntentId:Long,
    val merchantAccountId:String,
    val amountValue: Long,
    val currency: String,
    val sourceAccount: String,
    val targetAccount: String,
    val transferType: String,
    val status: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
