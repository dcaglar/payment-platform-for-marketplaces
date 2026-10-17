package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.command.CreateAccountCommand
import com.dogancaglar.paymentservice.domain.model.account.MerchantAccount
import com.dogancaglar.paymentservice.domain.model.account.SellerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.ports.inbound.usecases.CreateAccountUseCase
import com.dogancaglar.paymentservice.ports.outbound.AccountCreationTransactionalFacadePort
import org.slf4j.LoggerFactory

/**
 * Creates a merchant account from its command: the merchant, each seller, and the ledger accounts each of them
 * owns, all in one transaction. The platform's own ledger accounts (PLATFORM_CASH.GLOBAL.EUR, ...) are not part
 * of this: they exist per supported currency from the central-db changelog.
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

        val ledgerAccounts = mutableListOf<LedgerAccount>()
        for (type in MERCHANT_LEDGER_ACCOUNT_TYPES) {
            ledgerAccounts.add(LedgerAccount.createNew(type, merchant.accountCode, null, merchant.currency))
        }
        val sellers = mutableListOf<SellerAccount>()
        for (sellerCode in cmd.sellerAccountCodes) {
            val seller = SellerAccount.createNew(sellerCode, merchant.accountCode)
            sellers.add(seller)
            for (type in SELLER_LEDGER_ACCOUNT_TYPES) {
                ledgerAccounts.add(
                    LedgerAccount.createNew(type, merchant.accountCode, seller.accountCode, merchant.currency)
                )
            }
        }

        val created = accountCreationFacadePort.createMerchantAccount(merchant, sellers, ledgerAccounts)
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

    companion object {
        /** The ledger accounts every merchant gets, in its currency: `TYPE.MERCHANT.CURRENCY`. */
        val MERCHANT_LEDGER_ACCOUNT_TYPES = listOf(
            LedgerAccountType.AUTH_RECEIVABLE,
            LedgerAccountType.AUTH_LIABILITY,
            LedgerAccountType.CAPTURE_SUSPENSE,
            LedgerAccountType.MERCHANT_DIRECT_PAYABLE,
            LedgerAccountType.MERCHANT_COMMISSION_PAYABLE,
            LedgerAccountType.PLATFORM_FEE_RESERVE
        )

        /** The ledger accounts every seller gets, in its merchant's currency: `TYPE.MERCHANT.SELLER.CURRENCY`. */
        val SELLER_LEDGER_ACCOUNT_TYPES = listOf(LedgerAccountType.SELLER_PAYABLE)
    }
}
