package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.model.account.AccountStatus
import com.dogancaglar.paymentservice.domain.model.common.Currency

/** A ledger account as the account lookups return it (from the ledger_account_directory view). */
data class AccountProfile(
    val accountCode: String,
    val type: LedgerAccountType,
    val masterAccountCode: String,
    val subEntityId: String?,
    val currency: Currency,
    val status: AccountStatus
)
