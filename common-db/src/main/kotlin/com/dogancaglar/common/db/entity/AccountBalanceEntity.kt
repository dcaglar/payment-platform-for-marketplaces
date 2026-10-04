package com.dogancaglar.common.db.entity

import java.time.Instant

/**
 * Entity representing an account balance snapshot in the database.
 */
data class AccountBalanceEntity(
    val accountCode: String, // Primary key: e.g., "MERCHANT_PAYABLE.MERCHANT-456"
    val balance: Long,
    val lastAppliedEntryId: Long,
    val lastSnapshotAt: Instant,
    val updatedAt: Instant
)
