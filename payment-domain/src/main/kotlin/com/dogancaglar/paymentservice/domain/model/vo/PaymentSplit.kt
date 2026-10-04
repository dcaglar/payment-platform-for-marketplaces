package com.dogancaglar.paymentservice.domain.model.vo

import com.dogancaglar.paymentservice.domain.model.common.Amount

data class PaymentSplit(
    val sellerId: SellerId,
    val amount: Amount
)
