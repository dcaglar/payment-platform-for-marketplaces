package com.dogancaglar.paymentservice.ports.outbound

/**
 * Port for updating account balance deltas in cache (Redis).
 */
interface AccountBalanceCachePort {

    /**
     * Calculates real-time balance: snapshotBalance + delta.
     */
    fun getRealTimeBalance(accountId: String, snapshotBalance: Long): Long

    // Adds delta, bumps watermark, sets TTL, marks dirty
    fun addDeltaAndWatermark(accountCode: String, delta: Long, upToEntryId: Long)

    // Reads & zeroes delta, returns (delta, watermark)
    fun getAndResetDeltaWithWatermark(accountCode: String): Pair<Long, Long> // (delta, upToEntryId)

    // Adds account to dirty set (used by snapshot job)
    fun markDirty(accountCode: String) // add to a Redis SET for the snapshot job

    fun getDirtyAccounts(): Set<String> // 👈 new
}
