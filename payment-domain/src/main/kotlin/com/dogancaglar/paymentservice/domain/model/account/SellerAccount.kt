package com.dogancaglar.paymentservice.domain.model.account

import com.dogancaglar.paymentservice.domain.exception.AccountDomainException
import com.dogancaglar.paymentservice.domain.model.common.require
import com.dogancaglar.paymentservice.domain.model.ledger.AccountOwner
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType

/** A seller under one merchant ([masterAccountCode]). Its ledger accounts are generated from it ([ledgerAccounts]). */
data class SellerAccount private constructor(
    override val accountCode: String,
    override val status: AccountStatus,
    val masterAccountCode: String
) : Account() {

    /** One ledger account per seller-level type, in its merchant's currency. */
    fun ledgerAccounts(merchant: MerchantAccount): List<LedgerAccount> {
        require(merchant.accountCode == masterAccountCode) {
            AccountDomainException.InvariantViolationException(
                "Seller $accountCode belongs to $masterAccountCode, not to ${merchant.accountCode}"
            )
        }
        val accounts = mutableListOf<LedgerAccount>()
        for (type in LedgerAccountType.entries) {
            if (type.owner == AccountOwner.SELLER) {
                accounts.add(LedgerAccount.createNew(type, masterAccountCode, accountCode, merchant.currency))
            }
        }
        return accounts
    }

    companion object {
        fun createNew(
            accountCode: String,
            masterAccountCode: String,
            status: AccountStatus = AccountStatus.ACTIVE
        ): SellerAccount {
            require(AccountCodes.isValidOwnerCode(accountCode)) {
                AccountDomainException.InvariantViolationException("Invalid seller account code: '$accountCode'")
            }
            require(
                AccountCodes.isValidOwnerCode(masterAccountCode)
            ) {
                AccountDomainException.InvariantViolationException(
                    "Invalid merchant account code: '$masterAccountCode'"
                )
            }
            require(accountCode != masterAccountCode) {
                AccountDomainException.InvariantViolationException(
                    "A seller cannot have its merchant's code: $accountCode"
                )
            }
            return SellerAccount(accountCode, status, masterAccountCode)
        }

        /** Rebuilds from persisted state. Trusts the stored data. */
        fun rehydrate(accountCode: String, status: AccountStatus, masterAccountCode: String): SellerAccount =
            SellerAccount(accountCode, status, masterAccountCode)
    }
}
