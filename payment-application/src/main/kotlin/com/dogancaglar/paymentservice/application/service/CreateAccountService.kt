package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.command.CreateAccountCommand
import com.dogancaglar.paymentservice.domain.model.account.MerchantAccount
import com.dogancaglar.paymentservice.domain.model.account.PlatformAccount
import com.dogancaglar.paymentservice.domain.model.account.SellerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount
import com.dogancaglar.paymentservice.ports.inbound.usecases.CreateAccountUseCase
import com.dogancaglar.paymentservice.ports.outbound.AccountCreationTransactionalFacadePort
import org.slf4j.LoggerFactory

/**
 * Creates a merchant account from its command: the merchant, each seller, and the ledger accounts each of them
 * owns, all in one transaction. The platform's ledger accounts for the merchant's currency are created too when
 * this is the first merchant in that currency.
 */
class CreateAccountService(
    private val accountCreationFacadePort: AccountCreationTransactionalFacadePort
) : CreateAccountUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun create(cmd: CreateAccountCommand) {
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

        val sellers = mutableListOf<SellerAccount>()
        val ledgerAccounts = mutableListOf<LedgerAccount>()
        ledgerAccounts.addAll(merchant.ledgerAccounts())
        for (sellerCode in cmd.sellerAccountCodes) {
            val seller = SellerAccount.createNew(sellerCode, merchant.accountCode)
            sellers.add(seller)
            ledgerAccounts.addAll(seller.ledgerAccounts(merchant))
        }
        val platformLedgerAccounts = PlatformAccount.createNew().ledgerAccounts(merchant.currency)

        val created = accountCreationFacadePort.createMerchantAccount(
            merchant,
            sellers,
            ledgerAccounts,
            platformLedgerAccounts
        )
        if (!created) {
            // idempotent: the merchant exists already (a repeat of this request), so there is nothing to do
            logger.info("Merchant account {} already exists, nothing written", merchant.accountCode)
            return
        }
        logger.info(
            "Created merchant account {} with {} seller(s) and {} ledger account(s)",
            merchant.accountCode,
            sellers.size,
            ledgerAccounts.size
        )
    }
}
