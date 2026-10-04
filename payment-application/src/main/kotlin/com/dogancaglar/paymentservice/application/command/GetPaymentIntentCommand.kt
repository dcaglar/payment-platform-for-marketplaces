package com.dogancaglar.paymentservice.application.command

import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId

data class GetPaymentIntentCommand(
    val paymentIntentId: PaymentIntentId,
    /** The caller's merchant: only its own intents are found. */
    val merchantAccount: String
)
