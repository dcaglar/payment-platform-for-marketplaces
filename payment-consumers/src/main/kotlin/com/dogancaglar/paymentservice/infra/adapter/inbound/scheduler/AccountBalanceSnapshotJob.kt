package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.balance.AccountBalanceSnapshot
import com.dogancaglar.paymentservice.ports.outbound.AccountBalanceCachePort
import com.dogancaglar.paymentservice.ports.outbound.AccountBalanceSnapshotPort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class AccountBalanceSnapshotJob(
    private val cachePort: AccountBalanceCachePort,
    private val snapshotPort: AccountBalanceSnapshotPort,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${account-balance.snapshot-interval:PT1M}")
    fun mergeDeltasToSnapshots() {
        logger.debug("🔁 Starting AccountBalanceSnapshotJob")

        // a failure propagates: Spring's scheduler logs it and the next run tries again
        val dirtyAccounts = cachePort.getDirtyAccounts()
        if (dirtyAccounts.isEmpty()) {
            logger.debug("No dirty accounts to merge")
            return
        }

        dirtyAccounts.forEach { accountCode ->
            val (delta, upToEntryId) = cachePort.getAndResetDeltaWithWatermark(accountCode)
            if (delta == 0L) return@forEach

            val current = snapshotPort.getSnapshot(accountCode)
                ?: AccountBalanceSnapshot(accountCode, 0L, 0L, Utc.nowLocalDateTime(), Utc.nowLocalDateTime())

            val newBalance = current.balance + delta
            val newWatermark = maxOf(current.lastAppliedEntryId, upToEntryId)

            val updated = current.copy(
                balance = newBalance,
                lastAppliedEntryId = newWatermark,
                lastSnapshotAt = Utc.nowLocalDateTime(),
                updatedAt = Utc.nowLocalDateTime()
            )

            snapshotPort.saveSnapshot(updated)
            logger.debug(
                "✅ Merged Δ{} for {}, new balance={}, watermark={}",
                delta,
                accountCode,
                newBalance,
                newWatermark
            )
        }
    }
}
