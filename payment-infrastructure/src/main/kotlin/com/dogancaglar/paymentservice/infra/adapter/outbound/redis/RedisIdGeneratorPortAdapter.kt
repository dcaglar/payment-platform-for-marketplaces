package com.dogancaglar.paymentservice.infra.adapter.outbound.redis

import com.dogancaglar.paymentservice.ports.outbound.ExternalIdGeneratorPort
import org.springframework.data.redis.core.StringRedisTemplate

class RedisIdGeneratorPortAdapter(
    private val redis: StringRedisTemplate
) : ExternalIdGeneratorPort {

    override fun nextId(namespace: String): Long {
        return redis.opsForValue().increment(namespace)
            ?: error("Redis ID generation failed for namespace: $namespace")
    }

    // Optional: for recovery / fallback
    override fun getRawValue(namespace: String): Long? {
        return redis.opsForValue().get(namespace)?.toLongOrNull()
    }

    override fun setMinValue(namespace: String, value: Long) {
        val current = getRawValue(namespace) ?: 0
        if (value > current) {
            redis.opsForValue().set(namespace, value.toString())
        }
    }
}
