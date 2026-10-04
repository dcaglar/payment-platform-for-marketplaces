package com.dogancaglar.paymentservice.adapter.inbound.rest.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = PaymentMethodDTO.CardToken::class, name = "CardToken")
)
sealed class PaymentMethodDTO {

    data class CardToken(
        val token: String,
        val cvc: String? = null // Optional, depending on PSP config
    ) : PaymentMethodDTO()
}
