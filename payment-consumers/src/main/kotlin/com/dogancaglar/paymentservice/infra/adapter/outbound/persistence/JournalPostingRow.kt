package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

/** One posting with its journal entry, as LedgerMapper.findPostingsByTxId / findPostingsByPaymentId read them. */
data class JournalPostingRow(
    val journalId: String,
    val globalJournalEntryId: Long,
    val journalType: String,
    val journalName: String?,
    val paymentId: Long,
    val txId: Long?,
    val reason: String?,
    val accountCode: String,
    val accountType: String,
    val direction: String,
    val amount: Long,
    val currency: String
)
