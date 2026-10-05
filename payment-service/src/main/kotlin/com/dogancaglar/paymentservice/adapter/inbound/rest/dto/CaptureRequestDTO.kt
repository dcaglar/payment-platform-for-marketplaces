package com.dogancaglar.paymentservice.adapter.inbound.rest.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class CaptureRequestDTO(
    @field:NotBlank
    val merchantAccount: String,
    @field:NotNull
    @field:Valid
    val amount: AmountDto,
)
