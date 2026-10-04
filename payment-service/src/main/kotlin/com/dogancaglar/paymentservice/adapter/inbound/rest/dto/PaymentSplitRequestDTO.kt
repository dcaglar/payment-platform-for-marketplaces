package com.dogancaglar.paymentservice.adapter.inbound.rest.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * PaymentSplitRequestDTO
 *
 * REST-layer DTO for a single split routing instruction in the inbound
 * CreatePaymentIntent API request.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = PaymentSplitRequestDTO.BalanceAccount::class, name = "BalanceAccount"),
    JsonSubTypes.Type(value = PaymentSplitRequestDTO.Commission::class, name = "Commission")
)
sealed class PaymentSplitRequestDTO {
    abstract val amount: AmountDto

    data class BalanceAccount(
        @field:NotBlank
        val account: String,

        @field:NotNull
        @field:Valid
        override val amount: AmountDto
    ) : PaymentSplitRequestDTO()

    data class Commission(
        @field:NotNull
        @field:Valid
        override val amount: AmountDto
    ) : PaymentSplitRequestDTO()
}
