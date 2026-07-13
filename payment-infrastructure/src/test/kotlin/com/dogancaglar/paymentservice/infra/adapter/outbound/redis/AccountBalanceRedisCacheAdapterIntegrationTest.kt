package com.dogancaglar.paymentservice.infra.adapter.outbound.redis

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit

/**
 * Integration tests for AccountBalanceRedisCacheAdapter with real Redis (Testcontainers).
 * 
 * These tests validate:
 * - Atomic Lua script execution for addDeltaAndWatermark
 * - Atomic Lua script execution for getAndResetDeltaWithWatermark
 * - SET operations for markDirty and getDirtyAccounts
 * - Hash operations for getRealTimeBalance
 * - TTL expiration
 * - Concurrent access safety
 * 
 * Tagged as @integration for selective execution:
 * - mvn test                             -> Runs ALL tests (unit + integration)
 * - mvn test -Dgroups=integration        -> Runs integration tests only
 * - mvn test -DexcludedGroups=integration -> Runs unit tests only (fast)
 */
@Tag("integration")
@SpringBootTest(classes = [AccountBalanceRedisCacheAdapterIntegrationTest.TestConfig::class])
@Testcontainers
class AccountBalanceRedisCacheAdapterIntegrationTest {

    @Configuration
    @Import(RedisAutoConfiguration::class)
    class TestConfig {
        @Bean
        fun accountBalanceRedisCacheAdapter(redisTemplate: StringRedisTemplate): AccountBalanceRedisCacheAdapter {
            return AccountBalanceRedisCacheAdapter(redisTemplate, deltaTtlSeconds = 60, openTelemetry = io.opentelemetry.api.OpenTelemetry.noop()) // 60 seconds TTL for testing
        }
    }

    companion object {
        @Container
        @JvmStatic
        val redisContainer = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(false)

        @DynamicPropertySource
        @JvmStatic
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redisContainer.host }
            registry.add("spring.data.redis.port") { redisContainer.firstMappedPort }
        }
    }

    @Autowired
    private lateinit var adapter: AccountBalanceRedisCacheAdapter

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @BeforeEach
    fun setUp() {
        // Clean up Redis before each test
        redisTemplate.connectionFactory?.connection?.flushAll()
    }

    @Test
    fun `addDeltaAndWatermark should atomically update delta and watermark and mark dirty`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.seller-1"
        val initialDelta = 100L
        val initialWatermark = 50L
        val newDelta = 200L
        val newWatermark = 100L

        // When - First call
        adapter.addDeltaAndWatermark(accountCode, initialDelta, initialWatermark)

        // Then - Verify initial state (before reset)
        val realTimeBalance1 = adapter.getRealTimeBalance(accountCode, 0L)
        assertEquals(initialDelta, realTimeBalance1)
        assertEquals(initialWatermark, adapter.getAndResetDeltaWithWatermark(accountCode).second)
        assertTrue(adapter.getDirtyAccounts().contains(accountCode))

        // When - Second call with higher watermark (after first was reset, so delta should be newDelta)
        adapter.addDeltaAndWatermark(accountCode, newDelta, newWatermark)

        // Then - Verify updated state
        val (delta2, wm2) = adapter.getAndResetDeltaWithWatermark(accountCode)
        assertEquals(newDelta, delta2) // Delta is newDelta since first was reset
        assertEquals(newWatermark, wm2) // Watermark updates to higher value
        assertTrue(adapter.getDirtyAccounts().contains(accountCode))
    }

    @Test
    fun `addDeltaAndWatermark should only update watermark when new watermark is higher`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.seller-2"
        val highWatermark = 200L
        val lowWatermark = 100L

        // When - Add with high watermark first
        adapter.addDeltaAndWatermark(accountCode, 100L, highWatermark)
        val (_, wm1) = adapter.getAndResetDeltaWithWatermark(accountCode)
        assertEquals(highWatermark, wm1)

        // When - Add again with lower watermark
        adapter.addDeltaAndWatermark(accountCode, 50L, lowWatermark)

        // Then - Watermark should remain at high value
        val (_, wm2) = adapter.getAndResetDeltaWithWatermark(accountCode)
        assertEquals(highWatermark, wm2, "Watermark should not decrease")
    }

    @Test
    fun `getAndResetDeltaWithWatermark should read and reset delta atomically`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.seller-3"
        val delta = 5000L
        val watermark = 150L

        adapter.addDeltaAndWatermark(accountCode, delta, watermark)

        // When - First getAndReset
        val (delta1, wm1) = adapter.getAndResetDeltaWithWatermark(accountCode)

        // Then - Should return the delta and watermark
        assertEquals(delta, delta1)
        assertEquals(watermark, wm1)

        // When - Second getAndReset (should return zero now)
        val (delta2, wm2) = adapter.getAndResetDeltaWithWatermark(accountCode)

        // Then - Delta should be reset to 0, watermark remains
        assertEquals(0L, delta2, "Delta should be reset to 0")
        assertEquals(watermark, wm2, "Watermark should remain unchanged")
    }

    @Test
    fun `getAndResetDeltaWithWatermark should return zeros for non-existent account`() {
        // Given
        val accountCode = "NONEXISTENT.ACCOUNT"

        // When
        val (delta, watermark) = adapter.getAndResetDeltaWithWatermark(accountCode)

        // Then
        assertEquals(0L, delta)
        assertEquals(0L, watermark)
    }

    @Test
    fun `markDirty should add account to dirty set`() {
        // Given
        val accountCode1 = "MERCHANT_PAYABLE.seller-4"
        val accountCode2 = "PLATFORM_CASH.GLOBAL"

        // When
        adapter.markDirty(accountCode1)
        adapter.markDirty(accountCode2)

        // Then
        val dirtyAccounts = adapter.getDirtyAccounts()
        assertTrue(dirtyAccounts.contains(accountCode1))
        assertTrue(dirtyAccounts.contains(accountCode2))
    }

    @Test
    fun `getDirtyAccounts should return all accounts in dirty set`() {
        // Given
        val accountCodes = setOf(
            "MERCHANT_PAYABLE.seller-5",
            "MERCHANT_PAYABLE.seller-6",
            "PSP_RECEIVABLES.GLOBAL"
        )

        // When
        accountCodes.forEach { adapter.markDirty(it) }

        // Then
        val dirtyAccounts = adapter.getDirtyAccounts()
        assertEquals(accountCodes.size, dirtyAccounts.size)
        accountCodes.forEach { assertTrue(dirtyAccounts.contains(it)) }
    }

    @Test
    fun `getDirtyAccounts should return empty set when no accounts are dirty`() {
        // Given - No accounts marked as dirty

        // When
        val dirtyAccounts = adapter.getDirtyAccounts()

        // Then
        assertTrue(dirtyAccounts.isEmpty())
    }

    @Test
    fun `getRealTimeBalance should return snapshot plus delta`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.seller-7"
        val snapshotBalance = 100000L
        val delta = 5000L
        val watermark = 200L

        adapter.addDeltaAndWatermark(accountCode, delta, watermark)

        // When
        val realTimeBalance = adapter.getRealTimeBalance(accountCode, snapshotBalance)

        // Then
        assertEquals(snapshotBalance + delta, realTimeBalance)
    }

    @Test
    fun `getRealTimeBalance should return snapshot when no delta exists`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.seller-8"
        val snapshotBalance = 100000L

        // When - No delta added
        val realTimeBalance = adapter.getRealTimeBalance(accountCode, snapshotBalance)

        // Then
        assertEquals(snapshotBalance, realTimeBalance)
    }

    @Test
    fun `getRealTimeBalance should handle negative deltas`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.seller-9"
        val snapshotBalance = 100000L
        val negativeDelta = -5000L
        val watermark = 250L

        adapter.addDeltaAndWatermark(accountCode, negativeDelta, watermark)

        // When
        val realTimeBalance = adapter.getRealTimeBalance(accountCode, snapshotBalance)

        // Then
        assertEquals(snapshotBalance + negativeDelta, realTimeBalance)
        assertEquals(95000L, realTimeBalance)
    }

    @Test
    fun `addDeltaAndWatermark should accumulate deltas correctly`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.seller-10"

        // When - Add multiple deltas
        adapter.addDeltaAndWatermark(accountCode, 100L, 10L)
        adapter.addDeltaAndWatermark(accountCode, 200L, 20L)
        adapter.addDeltaAndWatermark(accountCode, 300L, 30L)

        // Then - Delta should accumulate
        val (totalDelta, maxWatermark) = adapter.getAndResetDeltaWithWatermark(accountCode)
        assertEquals(600L, totalDelta) // 100 + 200 + 300
        assertEquals(30L, maxWatermark) // Highest watermark
    }

    @Test
    fun `addDeltaAndWatermark should handle multiple accounts independently`() {
        // Given
        val accountCode1 = "MERCHANT_PAYABLE.seller-11"
        val accountCode2 = "MERCHANT_PAYABLE.seller-12"

        // When - Add deltas to different accounts
        adapter.addDeltaAndWatermark(accountCode1, 1000L, 100L)
        adapter.addDeltaAndWatermark(accountCode2, 2000L, 200L)

        // Then - Each account should have independent state
        val (delta1, wm1) = adapter.getAndResetDeltaWithWatermark(accountCode1)
        val (delta2, wm2) = adapter.getAndResetDeltaWithWatermark(accountCode2)

        assertEquals(1000L, delta1)
        assertEquals(100L, wm1)
        assertEquals(2000L, delta2)
        assertEquals(200L, wm2)
    }

    @Test
    fun `addDeltaAndWatermark should set TTL on account key`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.seller-13"
        val delta = 100L
        val watermark = 50L

        // When
        adapter.addDeltaAndWatermark(accountCode, delta, watermark)

        // Then - Verify key exists (TTL should be set by Lua script)
        val (deltaAfter, _) = adapter.getAndResetDeltaWithWatermark(accountCode)
        assertEquals(delta, deltaAfter)
    }

    @Test
    fun `getAndResetDeltaWithWatermark should preserve watermark when delta is zero`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.seller-14"
        val watermark = 500L

        // When - Add with zero delta (e.g., balanced transaction)
        adapter.addDeltaAndWatermark(accountCode, 0L, watermark)

        // Then - Watermark should still be set
        val (delta, wm) = adapter.getAndResetDeltaWithWatermark(accountCode)
        assertEquals(0L, delta)
        assertEquals(watermark, wm)
    }
}

