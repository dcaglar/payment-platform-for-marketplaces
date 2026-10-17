package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.account.MerchantAccount
import com.dogancaglar.paymentservice.domain.model.account.SellerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount

interface AccountCreationTransactionalFacadePort {

    /**
     * One transaction: the merchant, its sellers and their ledger accounts.
     * Returns false, writing nothing, if the merchant account already exists (creation is idempotent).
     */
    fun createMerchantAccount(
        merchant: MerchantAccount,
        sellers: List<SellerAccount>,
        ledgerAccounts: List<LedgerAccount>
    ): Boolean
}
