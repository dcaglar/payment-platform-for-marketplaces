package com.dogancaglar.paymentservice.infra.adapter.outbound.kafka

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.kafka.KafkaProperties

/**
 * Guards ordering layer L4: with retries, per-partition ordering survives
 * MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION > 1 ONLY because ENABLE_IDEMPOTENCE is on
 * (the broker sequences per producer). These two settings are a PAIR — raising
 * in-flight or disabling idempotence independently silently breaks partition ordering.
 */
class KafkaProducerConfigTest {

    private val config = KafkaProducerConfig(KafkaProperties(), "test-instance", "test-app")

    @Test
    fun `raw producer must pair idempotence=true with in-flight at most 5`() {
        val props = config.rawBatchProducerFactory().configurationProperties

        assertEquals(true, props[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG],
            "idempotence must stay enabled — it is what makes in-flight>1 retry-safe for ordering")
        val inFlight = (props[ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION] as Number).toInt()
        assertTrue(inFlight in 1..5,
            "max.in.flight must be <=5 for the idempotent producer's ordering guarantee (was $inFlight)")
    }

    @Test
    fun `raw producer must serialize values as plain strings - no envelope serializer on the raw path`() {
        val props = config.rawBatchProducerFactory().configurationProperties

        assertEquals(StringSerializer::class.java, props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG],
            "the relay streams pre-serialized raw bytes; the envelope serializer must not be on this path")
    }
}
