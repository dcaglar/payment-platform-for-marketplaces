package com.dogancaglar.paymentservice.adapter.inbound.rest.dto

data class CreatePaymentIntentResponseDTO(
    val paymentIntentId: String?,
    val clientSecret: String? = "",
    val buyerId: String,
    val orderId: String,
    val totalAmount: AmountDto,
    val status: String, // ✅ Added
    val createdAt: String, // ✅ Added
)
