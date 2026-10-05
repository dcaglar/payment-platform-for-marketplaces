package com.dogancaglar.paymentservice.infra.adapter.outbound.redis.client

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.paymentservice.application.events.CaptureRequested
import com.dogancaglar.paymentservice.application.util.RetryItem
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import kotlin.collections.plusAssign

@Repository
open class CaptureRetryRedisCache(
    private val redisTemplate: StringRedisTemplate,
    @Qualifier("myObjectMapper") private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val queue = "capture_retry_queue"
    private val inflight = "capture_retry_inflight" // ZSET: member = raw JSON, score = first-picked timestamp

    fun getRetryCount(paymentIntentId: String): Int {
        val retryKey = "retry:count:capture:$paymentIntentId"
        return redisTemplate.opsForValue()[retryKey]?.toInt() ?: 0
    }

    fun resetRetryCounter(paymentIntentId: String) {
        val retryKey = "retry:count:capture:$paymentIntentId"
        redisTemplate.delete(retryKey)
    }

    fun scheduleRetry(
        json: String,
        retryAt: Double
    ) {
        redisTemplate.opsForZSet().add(queue, json, retryAt)
    }

    fun zsetSize(): Long =
        redisTemplate.opsForZSet().zCard(queue) ?: 0L

    fun popDueToInflightDeserialized(max: Long = 1000): List<RetryItem> {
        val now = System.currentTimeMillis().toDouble()
        val rawItems = redisTemplate.execute(
            RedisCallback<List<ByteArray>> { conn ->
                val popped = conn.zSetCommands().zPopMin(queue.toByteArray(), max) ?: emptyList()
                if (popped.isEmpty()) return@RedisCallback emptyList()

                val due = mutableListOf<ByteArray>()
                val notDue = mutableListOf<Pair<ByteArray, Double>>()

                popped.forEach { tup ->
                    val value = tup.value // ByteArray
                    val score = tup.score
                    if (score <= now) {
                        // move to inflight with timestamp 'now'
                        conn.zSetCommands().zAdd(inflight.toByteArray(), now, value)
                        due += value
                    } else {
                        notDue += value to score
                    }
                }

                // Put not-due back to main queue with original score
                notDue.forEach { (valBytes, score) ->
                    conn.zSetCommands().zAdd(queue.toByteArray(), score, valBytes)
                }
                due
            }
        ) ?: emptyList()

        // Deserialize each ByteArray to EventEnvelope<CaptureRequested>
        val items = mutableListOf<RetryItem>()
        for (raw in rawItems) {
            try {
                val type = objectMapper.typeFactory.constructParametricType(
                    EventEnvelope::class.java,
                    CaptureRequested::class.java
                )
                val envelope: EventEnvelope<CaptureRequested> = objectMapper.readValue(String(raw), type)
                items += RetryItem(envelope, raw)
            } catch (e: JsonProcessingException) {
                // unreadable: drop it from inflight to avoid a poison loop. That capture will not be retried,
                // so log it findably (error, the item itself and the cause)
                logger.error("Unreadable capture retry item dropped: {}", String(raw).take(MAX_LOGGED_ITEM_CHARS), e)
                removeFromInflight(raw)
            }
        }
        return items
    }

    /** Remove one item from inflight ZSET by its exact raw JSON bytes. */
    fun removeFromInflight(raw: ByteArray) {
        redisTemplate.execute(
            RedisCallback<Long> { conn ->
                conn.zSetCommands().zRem(inflight.toByteArray(), raw) ?: 0L
            }
        )
    }

    /**
     * Reclaim inflight items older than [olderThanMs] back to the main queue.
     * Uses the current time as the new score (ready immediately).
     */
    fun reclaimInflight(olderThanMs: Long = 60_000) {
        val cutoff = (System.currentTimeMillis() - olderThanMs).toDouble()
        val nowScore = System.currentTimeMillis().toDouble()
        redisTemplate.execute(
            RedisCallback<Unit> { conn ->
                val members: MutableSet<ByteArray> =
                    conn.zSetCommands().zRangeByScore(inflight.toByteArray(), 0.0, cutoff) ?: return@RedisCallback

                // Requeue each stale member as due-now, then remove from inflight
                members.forEach { member ->
                    conn.zSetCommands().zAdd(queue.toByteArray(), nowScore, member)
                    conn.zSetCommands().zRem(inflight.toByteArray(), member)
                }
            }
        )
    }

    private companion object {
        const val MAX_LOGGED_ITEM_CHARS = 500 // enough to identify the item in the log
    }
}
