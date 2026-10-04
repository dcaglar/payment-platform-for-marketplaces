package com.dogancaglar.paymentservice.domain.model.balance

import java.time.LocalDateTime

/**
 * Represents a balance snapshot in the database.
 */
data class AccountBalanceSnapshot(
    val accountCode: String,
    val balance: Long,
    val lastAppliedEntryId: Long,
    val lastSnapshotAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
