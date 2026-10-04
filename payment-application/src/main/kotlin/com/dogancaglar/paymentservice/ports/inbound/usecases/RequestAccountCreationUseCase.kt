package com.dogancaglar.paymentservice.ports.inbound.usecases

import com.dogancaglar.paymentservice.application.command.CreateAccountCommand

/** Accepts a merchant account creation: validates it and queues it (outbox). The accounts are created later. */
interface RequestAccountCreationUseCase {
    fun requestCreation(cmd: CreateAccountCommand)
}
