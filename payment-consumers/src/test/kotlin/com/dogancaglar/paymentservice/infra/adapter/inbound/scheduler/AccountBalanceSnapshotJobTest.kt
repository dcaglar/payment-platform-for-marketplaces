package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.balance.AccountBalanceSnapshot
import com.dogancaglar.paymentservice.ports.outbound.AccountBalanceCachePort
import com.dogancaglar.paymentservice.ports.outbound.AccountBalanceSnapshotPort
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AccountBalanceSnapshotJobTest {

    private lateinit var accountBalanceCachePort: AccountBalanceCachePort
    private lateinit var accountBalanceSnapshotPort: AccountBalanceSnapshotPort
    private lateinit var job: AccountBalanceSnapshotJob

    @BeforeEach
    fun setUp() {
        accountBalanceCachePort = mockk(relaxed = true)
        accountBalanceSnapshotPort = mockk(relaxed = true)
        job = AccountBalanceSnapshotJob(
            cachePort = accountBalanceCachePort,
            snapshotPort = accountBalanceSnapshotPort,
        )
    }

    @Test
    fun `mergeDeltasToSnapshots should merge delta into snapshot and save`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.MERCHANT-456"
        val existingSnapshot = AccountBalanceSnapshot(
            accountCode = accountCode,
            balance = 100000L,
            lastAppliedEntryId = 100L,
            lastSnapshotAt = Utc.nowLocalDateTime().minusHours(1),
            updatedAt = Utc.nowLocalDateTime().minusHours(1)
        )

        val delta = 5000L
        val upToEntryId = 150L

        every { accountBalanceCachePort.getDirtyAccounts() } returns setOf(accountCode)
        every { accountBalanceCachePort.getAndResetDeltaWithWatermark(accountCode) } returns (delta to upToEntryId)
        every { accountBalanceSnapshotPort.getSnapshot(accountCode) } returns existingSnapshot
        every { accountBalanceSnapshotPort.saveSnapshot(any()) } just runs

        // When
        job.mergeDeltasToSnapshots()

        // Then - Should merge: 100000 + 5000 = 105000, watermark = max(100, 150) = 150
        verify(exactly = 1) {
            accountBalanceSnapshotPort.saveSnapshot(
                match { snapshot ->
                    snapshot.accountCode == accountCode &&
                        snapshot.balance == 105000L &&
                        snapshot.lastAppliedEntryId == 150L &&
                        snapshot.lastSnapshotAt.isAfter(existingSnapshot.lastSnapshotAt)
                }
            )
        }
    }

    @Test
    fun `mergeDeltasToSnapshots should create new snapshot when not exists`() {
        // Given
        val accountCode = "PLATFORM_CASH.GLOBAL"
        val delta = 10000L
        val upToEntryId = 200L

        every { accountBalanceCachePort.getDirtyAccounts() } returns setOf(accountCode)
        every { accountBalanceCachePort.getAndResetDeltaWithWatermark(accountCode) } returns (delta to upToEntryId)
        every { accountBalanceSnapshotPort.getSnapshot(accountCode) } returns null
        every { accountBalanceSnapshotPort.saveSnapshot(any()) } just runs

        // When
        job.mergeDeltasToSnapshots()

        // Then - Should create new snapshot with delta as balance
        verify(exactly = 1) {
            accountBalanceSnapshotPort.saveSnapshot(
                match { snapshot ->
                    snapshot.accountCode == accountCode &&
                        snapshot.balance == 10000L &&
                        snapshot.lastAppliedEntryId == 200L &&
                        snapshot.lastSnapshotAt != null &&
                        snapshot.updatedAt != null
                }
            )
        }
    }

    @Test
    fun `mergeDeltasToSnapshots should skip when delta is zero`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.MERCHANT-456"

        every { accountBalanceCachePort.getDirtyAccounts() } returns setOf(accountCode)
        every { accountBalanceCachePort.getAndResetDeltaWithWatermark(accountCode) } returns (0L to 100L)

        // When
        job.mergeDeltasToSnapshots()

        // Then - Should not fetch snapshot or save
        verify(exactly = 0) { accountBalanceSnapshotPort.getSnapshot(any()) }
        verify(exactly = 0) { accountBalanceSnapshotPort.saveSnapshot(any()) }
    }

    @Test
    fun `mergeDeltasToSnapshots should handle negative deltas`() {
        // Given
        val accountCode = "PLATFORM_CASH.GLOBAL"
        val existingSnapshot = AccountBalanceSnapshot(
            accountCode = accountCode,
            balance = 50000L,
            lastAppliedEntryId = 50L,
            lastSnapshotAt = Utc.nowLocalDateTime(),
            updatedAt = Utc.nowLocalDateTime()
        )

        val delta = -10000L
        val upToEntryId = 75L

        every { accountBalanceCachePort.getDirtyAccounts() } returns setOf(accountCode)
        every { accountBalanceCachePort.getAndResetDeltaWithWatermark(accountCode) } returns (delta to upToEntryId)
        every { accountBalanceSnapshotPort.getSnapshot(accountCode) } returns existingSnapshot
        every { accountBalanceSnapshotPort.saveSnapshot(any()) } just runs

        // When
        job.mergeDeltasToSnapshots()

        // Then - Should merge: 50000 - 10000 = 40000, watermark = max(50, 75) = 75
        verify(exactly = 1) {
            accountBalanceSnapshotPort.saveSnapshot(
                match { snapshot ->
                    snapshot.balance == 40000L &&
                        snapshot.lastAppliedEntryId == 75L
                }
            )
        }
    }

    @Test
    fun `mergeDeltasToSnapshots should process multiple dirty accounts`() {
        // Given
        val accountCode1 = "MERCHANT_PAYABLE.MERCHANT-456"
        val accountCode2 = "MERCHANT_PAYABLE.MERCHANT-789"

        val snapshot1 = AccountBalanceSnapshot(
            accountCode = accountCode1,
            balance = 100000L,
            lastAppliedEntryId = 100L,
            lastSnapshotAt = Utc.nowLocalDateTime(),
            updatedAt = Utc.nowLocalDateTime()
        )
        val snapshot2 = AccountBalanceSnapshot(
            accountCode = accountCode2,
            balance = 50000L,
            lastAppliedEntryId = 50L,
            lastSnapshotAt = Utc.nowLocalDateTime(),
            updatedAt = Utc.nowLocalDateTime()
        )

        every { accountBalanceCachePort.getDirtyAccounts() } returns setOf(accountCode1, accountCode2)
        every { accountBalanceCachePort.getAndResetDeltaWithWatermark(accountCode1) } returns (5000L to 150L)
        every { accountBalanceCachePort.getAndResetDeltaWithWatermark(accountCode2) } returns (3000L to 80L)
        every { accountBalanceSnapshotPort.getSnapshot(accountCode1) } returns snapshot1
        every { accountBalanceSnapshotPort.getSnapshot(accountCode2) } returns snapshot2
        every { accountBalanceSnapshotPort.saveSnapshot(any()) } just runs

        // When
        job.mergeDeltasToSnapshots()

        // Then - Should process both accounts
        verify(exactly = 1) {
            accountBalanceSnapshotPort.saveSnapshot(
                match { snapshot -> snapshot.accountCode == accountCode1 && snapshot.balance == 105000L }
            )
        }
        verify(exactly = 1) {
            accountBalanceSnapshotPort.saveSnapshot(
                match { snapshot -> snapshot.accountCode == accountCode2 && snapshot.balance == 53000L }
            )
        }
    }

    @Test
    fun `mergeDeltasToSnapshots should return early when no dirty accounts`() {
        // Given
        every { accountBalanceCachePort.getDirtyAccounts() } returns emptySet()

        // When
        job.mergeDeltasToSnapshots()

        // Then - Should not process anything
        verify(exactly = 0) { accountBalanceCachePort.getAndResetDeltaWithWatermark(any()) }
        verify(exactly = 0) { accountBalanceSnapshotPort.getSnapshot(any()) }
        verify(exactly = 0) { accountBalanceSnapshotPort.saveSnapshot(any()) }
    }

    @Test
    fun `mergeDeltasToSnapshots lets a failure propagate, so the scheduler logs it and the next run tries again`() {
        // Given
        every { accountBalanceCachePort.getDirtyAccounts() } throws RuntimeException("Redis error")

        // When/Then - the error comes out unchanged (it is not swallowed)
        val thrown = assertThrows<RuntimeException> { job.mergeDeltasToSnapshots() }
        assertEquals("Redis error", thrown.message)
    }
}
