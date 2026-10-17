package com.dogancaglar.paymentservice.domain.model.account

import com.dogancaglar.paymentservice.domain.exception.AccountDomainException
import com.dogancaglar.paymentservice.domain.model.common.require

/** A seller under one merchant ([masterAccountCode]). Its ledger account is created with it (CreateAccountService). */
data class SellerAccount private constructor(
    val accountCode: String,
    val status: AccountStatus,
    val masterAccountCode: String
) {

    companion object {
        fun createNew(
            accountCode: String,
            masterAccountCode: String,
            status: AccountStatus = AccountStatus.ACTIVE
        ): SellerAccount {
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
