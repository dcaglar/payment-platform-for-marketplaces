package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.balance.AccountBalanceSnapshot
import com.dogancaglar.paymentservice.ports.inbound.usecases.AccountBalanceReadUseCase
import com.dogancaglar.paymentservice.ports.outbound.AccountBalanceCachePort
import com.dogancaglar.paymentservice.ports.outbound.AccountBalanceSnapshotPort
import org.slf4j.LoggerFactory

class AccountBalanceReadService(
    private val cachePort: AccountBalanceCachePort,
    private val snapshotPort: AccountBalanceSnapshotPort
) : AccountBalanceReadUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getRealTimeBalance(accountCode: String): Long {
        val snapshot = snapshotPort.getSnapshot(accountCode)
        val snapshotBalanceVal = snapshot?.balance ?: 0L
        val realTimeBalanceVal = cachePort.getRealTimeBalance(accountCode, snapshotBalanceVal)
        logger.debug(
            "Real-time read for {}: snapshot={} + balance={} ",
            accountCode,
            snapshotBalanceVal,
            realTimeBalanceVal
        )
        return realTimeBalanceVal
    }

    override fun getStrongBalance(accountCode: String): Long {
        // 1️⃣ Atomically read and reset delta + watermark from Redis
        val (delta, upToEntryId) = cachePort.getAndResetDeltaWithWatermark(accountCode)

        // 2️⃣ Load current snapshot (or default)
        val current = snapshotPort.getSnapshot(accountCode)
            ?: AccountBalanceSnapshot(
                accountCode = accountCode,
                balance = 0L,
                lastAppliedEntryId = 0L,
                lastSnapshotAt = Utc.nowLocalDateTime(),
                updatedAt = Utc.nowLocalDateTime()
            )

        if (delta != 0L) {
            // 3️⃣ Compute new merged state
            val newBalance = current.balance + delta
            val newWatermark = maxOf(current.lastAppliedEntryId, upToEntryId)

            val updated = current.copy(
                balance = newBalance,
                lastAppliedEntryId = newWatermark,
                lastSnapshotAt = Utc.nowLocalDateTime(),
                updatedAt = Utc.nowLocalDateTime()
            )

            // 4️⃣ Persist (UPSERT guarded by last_applied_entry_id)
            snapshotPort.saveSnapshot(updated)
            logger.debug(
                "Strong merge applied for {}: Δ{} new balance={} watermark={}",
                accountCode,
                delta,
                newBalance,
                newWatermark
            )
            return newBalance
        }

        // 5️⃣ If no delta, return current snapshot as-is
        logger.debug("Strong read for {}: no delta to apply, returning snapshot={}", accountCode, current.balance)
        return current.balance
    }
}
