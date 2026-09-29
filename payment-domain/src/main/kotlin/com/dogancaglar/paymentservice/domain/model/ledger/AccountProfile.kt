package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.model.common.Currency

data class AccountProfile(
    val accountCode: String,
    val type: AccountType,
    val masterAccountCode: String,
    val subEntityId: String?,
    val currency: Currency,
    val category: AccountCategory,
    val country: String?,
    val status: AccountStatus
)

enum class AccountStatus { ACTIVE, SUSPENDED, CLOSED }