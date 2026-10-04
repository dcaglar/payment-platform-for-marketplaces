package com.dogancaglar.paymentservice.ports.inbound.usecases

import com.dogancaglar.paymentservice.application.command.CreatePaymentIntentCommand
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent

interface CreatePaymentIntentUseCase {
    fun create(cmd: CreatePaymentIntentCommand): PaymentIntent
}
