package com.dogancaglar.paymentservice.application.command

import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId

data class ProcessPaymentIntentUpdateCommand(
    val paymentIntentId: PaymentIntentId,
    val pspReference: String,
    val clientSecret: String?,
    val status: PaymentIntentStatus
)
