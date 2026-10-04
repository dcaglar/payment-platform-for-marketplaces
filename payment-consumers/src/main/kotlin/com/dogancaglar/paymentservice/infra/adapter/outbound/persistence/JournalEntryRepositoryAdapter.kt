package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.domain.model.ledger.Posting
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.LedgerMapper
import com.dogancaglar.paymentservice.ports.outbound.JournalEntryRepository
import org.springframework.stereotype.Repository

/** Reads journal entries with their postings from central-db (journal_entries, postings) back into the domain. */
@Repository
class JournalEntryRepositoryAdapter(
    private val ledgerMapper: LedgerMapper
) : JournalEntryRepository {

    override fun findByTxId(txId: TxId): List<JournalEntry> =
        toJournalEntries(ledgerMapper.findPostingsByTxId(txId.value))

    override fun findByPaymentId(paymentId: PaymentId): List<JournalEntry> =
        toJournalEntries(ledgerMapper.findPostingsByPaymentId(paymentId.value))

    // the rows come one per posting, ordered by journal entry: group them back, keeping the order
    private fun toJournalEntries(rows: List<JournalPostingRow>): List<JournalEntry> {
        val entries = mutableListOf<JournalEntry>()
        var i = 0
        while (i < rows.size) {
            val first = rows[i]
            val postings = mutableListOf<Posting>()
            while (i < rows.size && rows[i].journalId == first.journalId) {
                postings.add(toPosting(rows[i]))
                i++
            }
            var txId: TxId? = null
            if (first.txId != null) {
                txId = TxId(first.txId)
            }
            entries.add(
                JournalEntry.rehytrate(
                    id = first.journalId,
                    globalJournalEntryId = first.globalJournalEntryId,
                    txType = JournalType.valueOf(first.journalType),
                    name = first.journalName ?: first.journalType,
                    paymentId = PaymentId(first.paymentId),
                    txId = txId,
                    postings = postings,
                    reason = first.reason
                )
            )
        }
        return entries
    }

    private fun toPosting(row: JournalPostingRow): Posting {
        val account = LedgerAccount.fromCode(LedgerAccountType.valueOf(row.accountType), row.accountCode)
        val amount = Amount.of(row.amount, Currency(row.currency))
        if (row.direction == "DEBIT") {
            return Posting.Debit.create(account, amount)
        }
        return Posting.Credit.create(account, amount)
    }
}
