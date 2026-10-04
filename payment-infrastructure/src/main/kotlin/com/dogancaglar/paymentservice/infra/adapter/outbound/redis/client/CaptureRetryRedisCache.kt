package com.dogancaglar.paymentservice.infra.adapter.outbound.redis.client

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.paymentservice.application.events.CaptureRequested
import com.dogancaglar.paymentservice.application.util.RetryItem
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

    fun incrementAndGetRetryCount(paymentIntentId: String): Int {
        val retryKey = "retry:count:capture:$paymentIntentId"
        return redisTemplate.opsForValue().increment(retryKey)?.toInt() ?: 1
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

    fun pollDueRetries(): List<String> {
        val now = System.currentTimeMillis().toDouble()
        // get due items from the sorted set
        val dueItems = redisTemplate.opsForZSet().rangeByScore(queue, 0.0, now)
        // remove them from the sorted set
        dueItems?.forEach { json ->
            pureRemoveDueRetry(json)
        }
        return dueItems?.toList() ?: emptyList()
    }

    fun pollDueRetriesAtomic(max: Long = 1000): List<String> {
        val now = System.currentTimeMillis().toDouble()
        val connection = redisTemplate.connectionFactory?.connection
        val raw = connection?.zSetCommands()?.zPopMin(queue.toByteArray(), max)
        val (due, notDue) = raw?.partition { it.score <= now } ?: Pair(emptyList(), emptyList())
        // Re-insert not-due items
        notDue.forEach { connection?.zSetCommands()?.zAdd(queue.toByteArray(), it.score, it.value) }
        return due.map { String(it.value) }
    }

    fun pureRemoveDueRetry(json: String) {
        redisTemplate.opsForZSet().remove(queue, json)
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
            } catch (e: Exception) {
                // If we cannot deserialize, drop from inflight to avoid poison loops
                logger.warn("Failed to deserialize retry item, removing from inflight: \${e.message}")
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

    /** Number of items currently inflight. */
    fun inflightSize(): Long {
        return redisTemplate.execute(
            RedisCallback<Long> { conn ->
                conn.zSetCommands().zCard(inflight.toByteArray()) ?: 0L
            }
        ) ?: 0L
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
}
