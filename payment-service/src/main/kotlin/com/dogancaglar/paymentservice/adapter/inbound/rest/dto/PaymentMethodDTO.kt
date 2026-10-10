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

    /** The PSP's token for the card (e.g. pm_...). Never a card number or CVC: the PSP checked those when it
     * tokenized the card. */
    data class CardToken(
        val token: String
    ) : PaymentMethodDTO()
}
