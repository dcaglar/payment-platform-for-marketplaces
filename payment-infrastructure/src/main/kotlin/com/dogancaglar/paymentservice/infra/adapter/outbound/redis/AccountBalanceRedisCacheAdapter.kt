package com.dogancaglar.paymentservice.infra.adapter.outbound.redis

import com.dogancaglar.paymentservice.ports.outbound.AccountBalanceCachePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.connection.ReturnType
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import io.opentelemetry.api.OpenTelemetry

/**
 * Redis adapter for account balance delta cache.
 * Uses HINCRBY for atomic delta updates with TTL for automatic cleanup.
 */
@Component
class AccountBalanceRedisCacheAdapter(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${account-balance.delta-ttl-seconds:300}") // 5 minutes
    private val deltaTtlSeconds: Long,
    openTelemetry: OpenTelemetry
) : AccountBalanceCachePort {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val accPrefix = "balance:acc:"
    private val dirtySet = "balances:dirty"

    init {
        val meter = openTelemetry.meterBuilder("payment-infrastructure.redis.cache").build()
        meter.gaugeBuilder("redis_dirty_accounts_size")
            .setDescription("Number of accounts pending database snapshot flush")
            .ofLongs()
            .buildWithCallback { it.record(redisTemplate.opsForSet().size(dirtySet) ?: 0L) }
    }

    private val addDeltaScript = """
        local key = KEYS[1]
        local deltaField = 'delta'
        local wmField = 'last_entry_id'
        local delta = tonumber(ARGV[1])
        local upToId = tonumber(ARGV[2])
        local ttl = tonumber(ARGV[3])
        local dirtyKey = ARGV[4]

        local currentDelta = tonumber(redis.call('HINCRBY', key, deltaField, delta))
        local currentWatermark = tonumber(redis.call('HGET', key, wmField)) or 0
        if upToId > currentWatermark then
            redis.call('HSET', key, wmField, upToId)
        end
        redis.call('EXPIRE', key, ttl)
        redis.call('SADD', dirtyKey, key)
        return {currentDelta, upToId}
    """.trimIndent()

    private val getAndResetScript = """
        local key = KEYS[1]
        local deltaField = 'delta'
        local wmField = 'last_entry_id'
        local delta = tonumber(redis.call('HGET', key, deltaField)) or 0
        local watermark = tonumber(redis.call('HGET', key, wmField)) or 0
        if delta ~= 0 then
            redis.call('HSET', key, deltaField, 0)
        end
        return {delta, watermark}
    """.trimIndent()


    override fun addDeltaAndWatermark(accountCode: String, delta: Long, upToEntryId: Long) {
        val key = accPrefix + accountCode
        redisTemplate.execute<Any?> { conn ->
            conn.eval(
                addDeltaScript.toByteArray(StandardCharsets.UTF_8),
                ReturnType.MULTI,
                1,
                key.toByteArray(),
                delta.toString().toByteArray(),
                upToEntryId.toString().toByteArray(),
                deltaTtlSeconds.toString().toByteArray(),
                dirtySet.toByteArray()
            )
        }
        logger.debug("Added Δ{} for {} upToEntryId={}", delta, accountCode, upToEntryId)
    }

    override fun getAndResetDeltaWithWatermark(accountCode: String): Pair<Long, Long> {
        val key = accPrefix + accountCode
        val result = redisTemplate.execute<Any?> { conn ->
            conn.eval(
                getAndResetScript.toByteArray(StandardCharsets.UTF_8),
                ReturnType.MULTI,
                1,
                key.toByteArray()
            )
        } as? List<*> ?: return 0L to 0L

        val delta = (result[0] as? Long) ?: 0L
        val watermark = (result[1] as? Long) ?: 0L
        logger.debug("Fetched Δ{} and watermark {} for {}", delta, watermark, accountCode)
        return delta to watermark
    }

    override fun markDirty(accountCode: String) {
        val key = accPrefix + accountCode
        redisTemplate.opsForSet().add(dirtySet, key)
        redisTemplate.expire(dirtySet, deltaTtlSeconds, TimeUnit.SECONDS)
    }

    override fun getRealTimeBalance(accountId: String, snapshotBalance: Long): Long {
        val key = accPrefix + accountId
        val deltaStr = redisTemplate.opsForHash<String, String>().get(key, "delta")
        val delta = deltaStr?.toLongOrNull() ?: 0L
        return snapshotBalance + delta
    }

    override fun getDirtyAccounts(): Set<String> {
        return redisTemplate.opsForSet()
            .members("balances:dirty")
            ?.map { it.removePrefix("balance:acc:") }
            ?.toSet()
            ?: emptySet()
    }
}

