package com.dogancaglar.common.db.entity

/**
 * One row of the `ledger_accounts` table. [accountCode] is `TYPE.OWNER[.SELLER].CURRENCY`, the id stored on
 * postings; [ownerCode] a merchant or GLOBAL, [sellerCode] set for seller accounts.
 */
data class LedgerAccountEntity(
    val accountCode: String,
    val ledgerType: String,
    val ownerCode: String,
    val sellerCode: String?,
    val currency: String,
    val status: String
)
