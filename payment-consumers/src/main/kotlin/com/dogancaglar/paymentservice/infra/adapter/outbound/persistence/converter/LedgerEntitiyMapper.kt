package com.dogancaglar.common.db.converter

import com.dogancaglar.common.time.Utc
import com.dogancaglar.common.db.entity.JournalEntryEntity
import com.dogancaglar.common.db.entity.PostingEntity
import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.ledger.Posting

internal object LedgerEntitiyMapper {

    fun toJournalEntryEntity(entry: JournalEntry): JournalEntryEntity =
        JournalEntryEntity(
            id = entry.id,
            globalJournalEntryId = entry.globalJournalEntryId,
            journalType = entry.journalType.name,
            name = entry.name,
            paymentId = entry.paymentId.value, // 🛡️ Native Long mapping
            txId = entry.txId?.value,           // 🛡️ Native Long mapping
            createdAt = Utc.nowInstant(),
        )

    fun toPostingEntities(entry: JournalEntry): List<PostingEntity> =
        entry.postings.map { posting ->
            PostingEntity(
                journalId = entry.id,
                accountCode = posting.account.accountCode,
                accountType = posting.account.type.name,
                amount = posting.amount.quantity,
                direction = when (posting) {
                    is Posting.Debit -> "DEBIT"
                    is Posting.Credit -> "CREDIT"
                },
                currency = posting.amount.currency.currencyCode,
                createdAt = Utc.nowInstant(),
            )
        }
}
