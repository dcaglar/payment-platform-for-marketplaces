package com.dogancaglar.paymentservice.infra.adapter.outbound.redis

import com.dogancaglar.paymentservice.ports.outbound.EventDeduplicationPort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class RedisEventDedupAdapter(
    private val redis: StringRedisTemplate
) : EventDeduplicationPort {

    override fun exists(prefix: String, eventId: String): Boolean {
        return redis.hasKey(dedupKey(prefix, eventId))
    }

    override fun markProcessed(prefix: String, eventId: String, ttlSeconds: Long) {
        redis.opsForValue().set(
            dedupKey(prefix, eventId),
            "1",
            ttlSeconds,
            TimeUnit.SECONDS
        )
    }

    // One record per prefix (mqybe. consumer group), so groups reading the same topic never skip each other's events.
    private fun dedupKey(prefix: String, eventId: String) = "dedup:$prefix:$eventId"
}