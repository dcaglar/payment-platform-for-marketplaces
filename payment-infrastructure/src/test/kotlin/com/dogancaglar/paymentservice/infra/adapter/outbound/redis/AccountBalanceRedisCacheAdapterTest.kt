package com.dogancaglar.paymentservice.infra.adapter.outbound.redis

import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.TimeUnit
import org.springframework.data.redis.core.RedisCallback

/**
 * Unit tests for AccountBalanceRedisCacheAdapter using MockK.
 * 
 * Tests verify:
 * - Lua script execution for addDeltaAndWatermark
 * - Lua script execution for getAndResetDeltaWithWatermark
 * - SET operations for markDirty and getDirtyAccounts
 * - Hash operations for getRealTimeBalance
 * - Key prefixing
 * - TTL configuration
 */
class AccountBalanceRedisCacheAdapterTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var hashOperations: HashOperations<String, String, String>
    private lateinit var setOperations: SetOperations<String, String>
    private lateinit var redisConnection: RedisConnection
    private lateinit var adapter: AccountBalanceRedisCacheAdapter
    private val ttlSeconds = 300L

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk(relaxed = true)
        hashOperations = mockk(relaxed = true)
        setOperations = mockk(relaxed = true)
        redisConnection = mockk(relaxed = true)
        
        every { redisTemplate.opsForHash<String, String>() } returns hashOperations
        every { redisTemplate.opsForSet() } returns setOperations
        every { redisTemplate.expire(any<String>(), any<Long>(), any<TimeUnit>()) } returns true
        
        adapter = AccountBalanceRedisCacheAdapter(redisTemplate, ttlSeconds, io.opentelemetry.api.OpenTelemetry.noop())
    }

    @Test
    fun `addDeltaAndWatermark should execute Lua script with correct parameters`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.MERCHANT-456"
        val delta = 10000L
        val upToEntryId = 100L
        
        every { redisTemplate.execute<Any?>(any<RedisCallback<Any?>>()) } answers {
            val callback = firstArg<RedisCallback<Any?>>()
            callback.doInRedis(redisConnection)
            listOf<Any>(15000L, upToEntryId) // Return value from Lua script
        }

        // When
        adapter.addDeltaAndWatermark(accountCode, delta, upToEntryId)

        // Then - Verify execute was called (Lua script execution)
        verify(exactly = 1) { redisTemplate.execute<Any?>(any<RedisCallback<Any?>>()) }
    }

    @Test
    fun `getAndResetDeltaWithWatermark should execute Lua script and return delta and watermark`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.MERCHANT-456"
        val expectedDelta = 5000L
        val expectedWatermark = 150L
        
        every { redisTemplate.execute<Any?>(any<RedisCallback<Any?>>()) } answers {
            val callback = firstArg<RedisCallback<Any?>>()
            callback.doInRedis(redisConnection)
            listOf<Any>(expectedDelta, expectedWatermark) // Return value from Lua script
        }

        // When
        val result = adapter.getAndResetDeltaWithWatermark(accountCode)

        // Then
        assertEquals(expectedDelta, result.first)
        assertEquals(expectedWatermark, result.second)
        verify(exactly = 1) { redisTemplate.execute<Any?>(any<RedisCallback<Any?>>()) }
    }

    @Test
    fun `getAndResetDeltaWithWatermark should return zero when Lua script returns null`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.MERCHANT-456"
        
        every { redisTemplate.execute<Any?>(any<RedisCallback<Any?>>()) } returns null

        // When
        val result = adapter.getAndResetDeltaWithWatermark(accountCode)

        // Then
        assertEquals(0L, result.first)
        assertEquals(0L, result.second)
    }

    @Test
    fun `getAndResetDeltaWithWatermark should handle invalid result type gracefully`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.MERCHANT-456"
        
        every { redisTemplate.execute<Any?>(any<RedisCallback<Any?>>()) } returns "invalid-result"

        // When
        val result = adapter.getAndResetDeltaWithWatermark(accountCode)

        // Then - Should return zeros when result is not a List
        assertEquals(0L, result.first)
        assertEquals(0L, result.second)
    }

    @Test
    fun `markDirty should add account to dirty set and set TTL`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.MERCHANT-456"
        val expectedKey = "balance:acc:$accountCode"
        
        every { setOperations.add("balances:dirty", expectedKey) } returns 1L

        // When
        adapter.markDirty(accountCode)

        // Then
        verify(exactly = 1) { setOperations.add("balances:dirty", expectedKey) }
        verify(exactly = 1) { redisTemplate.expire("balances:dirty", ttlSeconds, TimeUnit.SECONDS) }
    }

    @Test
    fun `getRealTimeBalance should return snapshot plus delta from hash`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.MERCHANT-456"
        val snapshotBalance = 100000L
        val delta = 5000L
        val expectedKey = "balance:acc:$accountCode"
        
        every { hashOperations.get(expectedKey, "delta") } returns delta.toString()

        // When
        val result = adapter.getRealTimeBalance(accountCode, snapshotBalance)

        // Then
        assertEquals(snapshotBalance + delta, result)
        verify(exactly = 1) { hashOperations.get(expectedKey, "delta") }
    }

    @Test
    fun `getRealTimeBalance should return snapshot when delta is null`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.MERCHANT-456"
        val snapshotBalance = 100000L
        val expectedKey = "balance:acc:$accountCode"
        
        every { hashOperations.get(expectedKey, "delta") } returns null

        // When
        val result = adapter.getRealTimeBalance(accountCode, snapshotBalance)

        // Then
        assertEquals(snapshotBalance, result)
    }

    @Test
    fun `getRealTimeBalance should handle invalid delta string gracefully`() {
        // Given
        val accountCode = "MERCHANT_PAYABLE.MERCHANT-456"
        val snapshotBalance = 100000L
        val expectedKey = "balance:acc:$accountCode"
        
        every { hashOperations.get(expectedKey, "delta") } returns "invalid-number"

        // When
        val result = adapter.getRealTimeBalance(accountCode, snapshotBalance)

        // Then - Should treat invalid delta as 0
        assertEquals(snapshotBalance, result)
    }

    @Test
    fun `getDirtyAccounts should return set of account codes from dirty set`() {
        // Given
        val dirtyKeys = setOf(
            "balance:acc:MERCHANT_PAYABLE.MERCHANT-1",
            "balance:acc:MERCHANT_PAYABLE.MERCHANT-2",
            "balance:acc:PLATFORM_CASH.GLOBAL"
        )
        
        every { setOperations.members("balances:dirty") } returns dirtyKeys

        // When
        val result = adapter.getDirtyAccounts()

        // Then
        assertEquals(
            setOf("MERCHANT_PAYABLE.MERCHANT-1", "MERCHANT_PAYABLE.MERCHANT-2", "PLATFORM_CASH.GLOBAL"),
            result
        )
        verify(exactly = 1) { setOperations.members("balances:dirty") }
    }

    @Test
    fun `getDirtyAccounts should return empty set when no dirty accounts exist`() {
        // Given
        every { setOperations.members("balances:dirty") } returns null

        // When
        val result = adapter.getDirtyAccounts()

        // Then
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getDirtyAccounts should return empty set when dirty set is empty`() {
        // Given
        every { setOperations.members("balances:dirty") } returns emptySet()

        // When
        val result = adapter.getDirtyAccounts()

        // Then
        assertTrue(result.isEmpty())
    }
}

