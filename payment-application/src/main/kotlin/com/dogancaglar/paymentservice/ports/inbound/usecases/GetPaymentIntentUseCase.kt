package com.dogancaglar.paymentservice.ports.inbound.usecases

import com.dogancaglar.paymentservice.application.command.GetPaymentIntentCommand
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent

interface GetPaymentIntentUseCase {
    fun getPaymentIntent(cmd: GetPaymentIntentCommand): PaymentIntent
}
