package com.dogancaglar.paymentservice.adapter.inbound.rest.mapper

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.port.out.web.dto.AmountDto
import com.dogancaglar.port.out.web.dto.CurrencyEnum

object AmountMapper {

    fun toDomain(dto: AmountDto): Amount {
        return Amount.of(
            quantity = dto.quantity,
            currency = Currency(dto.currency.name)
        )
    }

    fun toDto(amount: Amount): AmountDto {
        return AmountDto(
            quantity = amount.quantity,
            currency = CurrencyEnum.valueOf(amount.currency.currencyCode)
        )
    }
}
