package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.kafka.metadata.PaymentEventMetadataCatalog
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.infra.adapter.outbound.redis.CaptureRetryQueueAdapter
import com.dogancaglar.paymentservice.ports.outbound.CentralOutboxWriterPort
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Component
class RetryDispatcherScheduler(
    private val retryQueue: CaptureRetryQueueAdapter,
    private val outboxEventFactoryPort: OutboxEventFactoryPort,
    private val centralOutboxWriterPort: CentralOutboxWriterPort,
    @param:Qualifier("retryDispatcherSpringScheduler") private val scheduler: ThreadPoolTaskScheduler,
    openTelemetry: OpenTelemetry
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val batchSize = AtomicInteger(0)
    private val running = AtomicBoolean(false)

    // the topic as an attribute, so dashboards can filter/group
    private val topicAttributes =
        Attributes.of(TOPIC, PaymentEventMetadataCatalog.CaptureRequestedMetadata.topic)
    private val processedAttributes =
        Attributes.of(TOPIC, PaymentEventMetadataCatalog.CaptureRequestedMetadata.topic, RESULT, "processed")
    private val failedAttributes =
        Attributes.of(TOPIC, PaymentEventMetadataCatalog.CaptureRequestedMetadata.topic, RESULT, "failed")

    private val meter = openTelemetry.meterBuilder("payment-consumers.redis.retry").build()

    private val retryEvents = meter.counterBuilder("redis_retry_events_total")
        .setDescription("Capture retries written to the outbox (result=processed) or not (result=failed)")
        .build()

    private val batchDuration = meter.histogramBuilder("redis_retry_dispatch_batch_seconds")
        .setDescription("Total time to dispatch a retry batch")
        .setUnit("s")
        .build()

    init {
        meter.gaugeBuilder("redis_retry_batch_size")
            .setDescription("Number of retry events processed in the last batch")
            .ofLongs()
            .buildWithCallback { it.record(batchSize.get().toLong(), topicAttributes) }
    }

    @Scheduled(fixedDelay = 5_000)
    fun dispatch() {
        if (!running.compareAndSet(false, true)) {
            logger.warn("Previous dispatch still running, skipping this run")
            return
        }
        scheduler.execute {
            try {
                dispatchOnce()
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * Hands the due capture retries on: one outbox event each, all written in ONE multi-row insert (atomic, one
     * round trip), then removed from inflight. If the write fails, they stay inflight and [reclaimInflight] puts them
     * back after 60 s. A crash between the write and the removal writes them again later: harmless, the capture
     * executor dedups on the event's deterministic id (it includes the attempt) and the PSP call carries the same
     * idempotency key.
     */
    fun dispatchOnce() {
        val startNs = System.nanoTime()
        val due = retryQueue.pollDueRetriesToInflight(POLL_LIMIT)
        batchSize.set(due.size)
        if (due.isEmpty()) {
            recordBatchDuration(startNs)
            logger.debug("RetryDispatcher: nothing due right now")
            return
        }

        val outboxEvents = mutableListOf<OutboxEvent>()
        for (item in due) {
            outboxEvents.add(outboxEventFactoryPort.create(item.envelope.data))
        }
        try {
            centralOutboxWriterPort.saveAll(outboxEvents)
        } catch (e: DataAccessException) {
            // handled here: nothing is lost, the items stay inflight and are reclaimed
            retryEvents.add(due.size.toLong(), failedAttributes)
            recordBatchDuration(startNs)
            logger.warn(
                "RetryDispatcher: writing {} capture retries to the outbox failed, they are reclaimed in 60 s",
                due.size,
                e
            )
            return
        }
        for (item in due) {
            retryQueue.removeFromInflight(item.raw)
        }
        retryEvents.add(due.size.toLong(), processedAttributes)
        recordBatchDuration(startNs)
        logger.debug("RetryDispatcher: {} capture retries written to the outbox", due.size)
    }

    /** Requeue stale inflight items (e.g., if we crashed after popping) */
    @Scheduled(fixedDelay = 30_000)
    fun reclaimInflight() {
        val before = System.currentTimeMillis()
        retryQueue.reclaimInflight(olderThanMs = 60_000) // keep > worst-case TX time
        val took = System.currentTimeMillis() - before
        logger.debug("Reclaimed stale inflight ({} ms)", took)
    }

    private fun recordBatchDuration(startNs: Long) {
        batchDuration.record((System.nanoTime() - startNs) / NANOS_PER_SECOND, topicAttributes)
    }

    private companion object {
        const val POLL_LIMIT = 1000L // how many to pop from Redis per tick
        const val NANOS_PER_SECOND = 1_000_000_000.0
        val TOPIC: AttributeKey<String> = AttributeKey.stringKey("topic")
        val RESULT: AttributeKey<String> = AttributeKey.stringKey("result")
    }
}
