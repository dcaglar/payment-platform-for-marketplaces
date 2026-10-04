package com.dogancaglar.port.out.web.dto

import java.time.Instant

/** A payment in the ledger, for finance: its txs, and all its journal entries in recorded order. */
data class PaymentDto(
    val paymentId: String,
    val merchantAccount: String,
    val txs: List<TxDto>,
    val journalEntries: List<JournalEntryDto>
)

/** One tx (AUTHORIZATION, CAPTURE, SETTLEMENT, …); [journalEntries] only when one tx is asked for. */
data class TxDto(
    val txId: String,
    val txType: String,
    val paymentId: String,
    val status: String,
    val amount: AmountDto,
    val acquirerReference: String?,
    val parentTxId: String?, // capture -> its authorization; settlement, refund -> its capture
    val settleStatus: String?,
    val createdAt: Instant,
    val detailUrl: String,
    val journalEntries: List<JournalEntryDto>? = null
)

/** A journal entry with its postings; debits and credits add up to the same total. */
data class JournalEntryDto(
    val id: String,
    val journalType: String,
    val name: String,
    val txId: String?,
    val postings: List<PostingDto>,
    val totalDebit: AmountDto,
    val totalCredit: AmountDto
)

data class PostingDto(
    val accountCode: String,
    val accountType: String,
    val direction: String, // DEBIT or CREDIT
    val amount: AmountDto
)
