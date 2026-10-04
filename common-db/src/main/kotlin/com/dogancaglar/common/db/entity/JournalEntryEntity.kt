package com.dogancaglar.common.db.entity

import java.time.Instant

data class JournalEntryEntity constructor(
    val id: String,
    val globalJournalEntryId: Long,
    val journalType: String,
    val name: String,
    val paymentId: Long, // 🛡️ Strict Domain Primitive
    val txId: Long?, // 🛡️ Strict Domain Primitive
    val createdAt: Instant
)
