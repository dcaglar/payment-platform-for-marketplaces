package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType

interface AccountDirectoryPort {
    // Platform-wide ("GLOBAL") and per-merchant accounts
    fun getAccountProfile(accountType: LedgerAccountType, masterAccountCode: String, currency: Currency): AccountProfile

    // An account that belongs to a sub entity (a seller) of the given merchant
    fun getSubEntityAccountProfile(
        accountType: LedgerAccountType,
        masterAccountCode: String,
        subEntityId: String,
        currency: Currency
    ): AccountProfile

    // All accounts of a sub entity (a seller), one per currency, found by its id alone
    fun getAccountProfilesBySubEntity(accountType: LedgerAccountType, subEntityId: String): List<AccountProfile>

    // All accounts of a merchant of the given type (no sub entity), one per currency
    fun getAccountProfilesByMaster(accountType: LedgerAccountType, masterAccountCode: String): List<AccountProfile>

    // All accounts of the given type of a merchant's sub entities (its sellers), ordered by sub entity and currency
    fun getSubEntityAccountProfilesByMaster(
        accountType: LedgerAccountType,
        masterAccountCode: String
    ): List<AccountProfile>

    fun getAccountByCode(accountCode: String): AccountProfile
}
