package com.dogancaglar.paymentservice.infra.adapter.outbound.redis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

/**
 * RedisEventDedupAdapter against a real Redis (Testcontainers).
 *
 * Guards the fan-out bug: two consumer groups read the same topic (e.g. journal.entries.recorded),
 * so one group marking an event processed must never make the other group skip it.
 */
@Tag("integration")
@SpringBootTest(classes = [RedisEventDedupAdapterIntegrationTest.TestConfig::class])
@Testcontainers
class RedisEventDedupAdapterIntegrationTest {

    @Configuration
    @Import(RedisAutoConfiguration::class)
    class TestConfig {
        @Bean
        fun redisEventDedupAdapter(redisTemplate: StringRedisTemplate): RedisEventDedupAdapter {
            return RedisEventDedupAdapter(redisTemplate)
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
    private lateinit var adapter: RedisEventDedupAdapter

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val eventId = "SETTLEMENT:pi_AzJ163wCAAA:230380971713757184:SUCCESS"
    private val allocationGroup = "webhook.capture.confirmed.processor"
    private val balanceGroup = "accaount.balance.consumer"

    @BeforeEach
    fun setUp() {
        redisTemplate.connectionFactory?.connection?.serverCommands()?.flushAll()
    }

    @Test
    fun `one group marking an event does not make another group skip it`() {
        adapter.markProcessed(allocationGroup, eventId, 60)

        assertTrue(adapter.exists(allocationGroup, eventId), "the group that processed it must see it as processed")
        assertFalse(adapter.exists(balanceGroup, eventId), "another group on the same topic must still process it")
    }

    @Test
    fun `the same group sees a redelivered event as processed`() {
        assertFalse(adapter.exists(balanceGroup, eventId))

        adapter.markProcessed(balanceGroup, eventId, 60)

        assertTrue(adapter.exists(balanceGroup, eventId))
    }

    @Test
    fun `key is consumer group plus the unchanged deterministic event id, with a TTL`() {
        adapter.markProcessed(balanceGroup, eventId, 60)

        val key = "dedup:$balanceGroup:$eventId"
        assertEquals("1", redisTemplate.opsForValue().get(key))
        val ttl = redisTemplate.getExpire(key)
        assertTrue(ttl in 1..60, "TTL must be set, was $ttl")
    }
}
