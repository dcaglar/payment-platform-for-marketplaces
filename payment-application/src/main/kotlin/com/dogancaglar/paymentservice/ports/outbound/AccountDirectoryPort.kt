package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType

interface AccountDirectoryPort {
    // Platform-wide ("GLOBAL") and per-merchant accounts
    fun getAccountProfile(accountType: AccountType, masterAccountCode: String, currency: Currency): AccountProfile

    // An account that belongs to a sub entity (a seller) of the given merchant
    fun getSubEntityAccountProfile(accountType: AccountType, masterAccountCode: String, subEntityId: String, currency: Currency): AccountProfile

    // All accounts of a sub entity (a seller), one per currency, found by its id alone
    fun getAccountProfilesBySubEntity(accountType: AccountType, subEntityId: String): List<AccountProfile>

    // All accounts of a merchant of the given type (no sub entity), one per currency
    fun getAccountProfilesByMaster(accountType: AccountType, masterAccountCode: String): List<AccountProfile>

    fun getAccountByCode(accountCode: String): AccountProfile
}
