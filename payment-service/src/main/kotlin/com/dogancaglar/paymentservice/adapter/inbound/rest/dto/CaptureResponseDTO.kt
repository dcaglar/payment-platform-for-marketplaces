package com.dogancaglar.paymentservice.adapter.inbound.rest.dto

data class CaptureResponseDTO(
    val publicPaymentIntentId: String?,
    val status: String, // ✅ Added
    val createdAt: String,
)
