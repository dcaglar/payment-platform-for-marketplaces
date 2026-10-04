package com.dogancaglar.common.db.entity

/**
 * One row of the `accounts` table. Which columns are filled depends on [kind]
 * (PLATFORM, MERCHANT, SELLER, LEDGER); the table's CHECK constraints enforce it.
 */
data class AccountEntity(
    val accountCode: String,
    val kind: String,
    val status: String,
    val parentCode: String?,
    val ledgerType: String?,
    val currency: String?,
    val isAutoCaptured: Boolean?,
    val isAutoSettled: Boolean?,
    val platformFeeFixed: Long?,
    val platformFeeBps: Int?,
    val profile: String? // JSON: legal name, address, industry (MERCHANT only)
)
