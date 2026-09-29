package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.common.db.entity.JournalEntryEntity
import com.dogancaglar.common.db.entity.PostingEntity
import org.apache.ibatis.annotations.Mapper

@Mapper
interface LedgerMapper {
    fun insertJournalEntry(entry: JournalEntryEntity): Int
    fun insertPosting(posting: PostingEntity): Int
}
