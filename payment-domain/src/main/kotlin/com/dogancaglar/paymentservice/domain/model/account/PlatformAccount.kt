package com.dogancaglar.paymentservice.domain.model.account

import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountOwner
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType

/** The platform itself (code GLOBAL): owns the platform-level ledger accounts, once per currency. */
class PlatformAccount private constructor(
    override val status: AccountStatus
) : Account() {

    override val accountCode: String = AccountCodes.PLATFORM

    /** One ledger account per platform-level type, in the given currency. */
    fun ledgerAccounts(currency: Currency): List<LedgerAccount> {
        val accounts = mutableListOf<LedgerAccount>()
        for (type in LedgerAccountType.entries) {
            if (type.owner == AccountOwner.PLATFORM) {
                accounts.add(LedgerAccount.createNew(type, accountCode, null, currency))
            }
        }
        return accounts
    }

    companion object {
        fun createNew(status: AccountStatus = AccountStatus.ACTIVE): PlatformAccount = PlatformAccount(status)
    }
}
