package com.dogancaglar.paymentservice.ports.inbound.usecases

import com.dogancaglar.paymentservice.application.command.CreateAccountCommand

/** Creates the merchant account, its sellers and all their ledger accounts, in one transaction. */
interface CreateAccountUseCase {
    fun create(cmd: CreateAccountCommand)
}
