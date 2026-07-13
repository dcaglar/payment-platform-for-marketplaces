package com.dogancaglar.paymentservice.infra.adapter.outbound.redis

import com.dogancaglar.common.event.EventEnvelopeFactory
import com.dogancaglar.common.logging.EventLogContext
import com.dogancaglar.paymentservice.application.events.CaptureRequested
import com.dogancaglar.paymentservice.application.util.RetryItem
import com.dogancaglar.paymentservice.infra.adapter.outbound.redis.client.CaptureRetryRedisCache
import com.dogancaglar.paymentservice.ports.outbound.RetryQueuePort
import com.dogancaglar.paymentservice.ports.outbound.SerializationPort
import io.opentelemetry.api.OpenTelemetry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component("captureRetryQueueAdapter")
class CaptureRetryQueueAdapter(
    private val captureRetryRedisCache: CaptureRetryRedisCache,
    openTelemetry: OpenTelemetry,
    val serializationPort: SerializationPort) : RetryQueuePort<CaptureRequested> {

    init {
        val meter = openTelemetry.meterBuilder("payment-infrastructure.redis.retry").build()
        meter.gaugeBuilder("redis_retry_zset_size")
            .setDescription("Number of entries pending in the Redis retry ZSet")
            .ofLongs()
            .buildWithCallback { it.record(captureRetryRedisCache.zsetSize()) }
    }

    private val logger = LoggerFactory.getLogger(CaptureRetryQueueAdapter::class.java)

    override fun scheduleRetry(
        event: CaptureRequested,
        backOffMillis: Long,
    ) {
        val totalStart = System.currentTimeMillis()
        try {
            val retryAt = System.currentTimeMillis() + backOffMillis

            val envelope = EventEnvelopeFactory.envelopeFor(
                data = event,
                aggregateId = event.publicPaymentIntentId,
                parentEventId = EventLogContext.getEventId()
            )
            val json = serializationPort.toJson(envelope)
            captureRetryRedisCache.scheduleRetry(json, retryAt.toDouble())
        } catch (e: Exception) {
            logger.error("❌ Exception during scheduleRetry for agg={}", event.publicPaymentIntentId, e)
            throw e
        }
    }

    // Optional ops helpers
    override fun getRetryCount(identifier: String): Int =
        captureRetryRedisCache.getRetryCount(identifier)

    override fun resetRetryCounter(identifier: String) {
        captureRetryRedisCache.resetRetryCounter(identifier)
    }


    /** New: pop to inflight and return [RetryItem]s. */
    override fun pollDueRetriesToInflight(maxBatchSize: Long): List<RetryItem> {
        // Use the new deserialized method - deserialization happens in cache layer (like Kafka)
        return captureRetryRedisCache.popDueToInflightDeserialized(maxBatchSize)
    }


    fun removeFromInflight(raw: ByteArray) =
        captureRetryRedisCache.removeFromInflight(raw)

    fun reclaimInflight(olderThanMs: Long = 60_000) =
        captureRetryRedisCache.reclaimInflight(olderThanMs)
}
