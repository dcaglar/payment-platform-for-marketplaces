package com.dogancaglar.paymentservice.ports.inbound.usecases

import com.dogancaglar.paymentservice.application.command.ProcessPaymentIntentUpdateCommand
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent

interface UpdatePaymentIntentUseCase {
    fun processUpdate(cmd: ProcessPaymentIntentUpdateCommand): PaymentIntent
}
