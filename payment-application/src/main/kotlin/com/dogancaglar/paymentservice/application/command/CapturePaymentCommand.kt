package com.dogancaglar.paymentservice.application.command

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId

data class CapturePaymentCommand(
    val paymentIntentId: PaymentIntentId,
    val merchantAccount: String,
    val amount: Amount
)
