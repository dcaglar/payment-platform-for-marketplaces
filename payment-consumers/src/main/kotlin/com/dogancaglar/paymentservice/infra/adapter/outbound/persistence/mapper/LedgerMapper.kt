package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.common.db.entity.JournalEntryEntity
import com.dogancaglar.common.db.entity.PostingEntity
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.JournalPostingRow
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface LedgerMapper {
    fun insertJournalEntry(entry: JournalEntryEntity): Int
    fun insertPosting(posting: PostingEntity): Int

    fun findPostingsByTxId(@Param("txId") txId: Long): List<JournalPostingRow>

    fun findPostingsByPaymentId(@Param("paymentId") paymentId: Long): List<JournalPostingRow>
}
