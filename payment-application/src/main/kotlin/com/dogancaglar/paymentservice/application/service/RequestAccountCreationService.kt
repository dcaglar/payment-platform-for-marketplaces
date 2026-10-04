package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.command.CreateAccountCommand
import com.dogancaglar.paymentservice.application.events.AccountCreationRequested
import com.dogancaglar.paymentservice.domain.model.account.MerchantAccount
import com.dogancaglar.paymentservice.domain.model.account.SellerAccount
import com.dogancaglar.paymentservice.ports.inbound.usecases.RequestAccountCreationUseCase
import com.dogancaglar.paymentservice.ports.outbound.CentralOutboxWriterPort
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import org.slf4j.LoggerFactory

/**
 * Accepts a merchant account creation and queues it: builds the domain accounts once (so invalid input is
 * rejected now, not later in the consumer), then writes outbox<account_creation_requested>.
 */
class RequestAccountCreationService(
    private val outboxEventFactoryPort: OutboxEventFactoryPort,
    private val centralOutboxWriterPort: CentralOutboxWriterPort
) : RequestAccountCreationUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun requestCreation(cmd: CreateAccountCommand) {
        // validation only: the same rules the consumer applies when it creates the accounts
        val merchant = MerchantAccount.createNew(
            accountCode = cmd.merchantAccountCode,
            legalName = cmd.legalName,
            address = cmd.address,
            industry = cmd.industry,
            currency = cmd.currency,
            platformFee = cmd.platformFee,
            isAutoCaptured = cmd.isAutoCaptured,
            isAutoSettled = cmd.isAutoSettled
        )
        for (sellerCode in cmd.sellerAccountCodes) {
            SellerAccount.createNew(sellerCode, merchant.accountCode)
        }

        val event = AccountCreationRequested.from(cmd)
        val outboxEvent = outboxEventFactoryPort.create(
            event = event,
            aggregateId = cmd.merchantAccountCode,
            partitionKey = cmd.merchantAccountCode
        )
        centralOutboxWriterPort.save(outboxEvent)
        logger.info(
            "Account creation requested for merchant {} with {} seller(s)",
            cmd.merchantAccountCode,
            cmd.sellerAccountCodes.size
        )
    }
}
