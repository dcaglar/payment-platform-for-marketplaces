package com.dogancaglar.common.db.entity

import java.time.Instant

data class PaymentIntentEntity(
    val paymentIntentId: Long,
    val pspReference: String? = "",
    val buyerId: String,
    val orderId: String,
    val merchantAccount: String,
    val processingModel: String,
    val totalAmountValue: Long,
    val currency: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val splitsJson: String,
    val cardBrand: String? = null,
    val cardLast4: String? = null
)
