/*package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.kafka.publisher.PaymentEventPublisher
import com.dogancaglar.common.kafka.metadata.PaymentEventMetadataCatalog
import com.dogancaglar.paymentservice.application.events.CaptureRequested
import com.dogancaglar.paymentservice.infra.adapter.outbound.redis.CaptureRetryQueueAdapter
import io.micrometer.core.instrument.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean

@Component
class RetryDispatcherScheduler(
    private val retryQueue: CaptureRetryQueueAdapter,
    @param:Qualifier("batchPaymentEventPublisher") private val publisher: PaymentEventPublisher,
    private val meterRegistry: MeterRegistry,
    @param:Qualifier("retryDispatcherSpringScheduler") private val scheduler: ThreadPoolTaskScheduler
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // tune these if you like (or expose via @Value)
    private val pollLimit = 1000L   // how many to pop from Redis per tick
    private val chunkSize = 300     // how many to send per Kafka transaction (atomic)

    private val batchSize = AtomicInteger(0)
    private val running = AtomicBoolean(false)

    // Static tag for your topic (so dashboards can filter/group)
    private val topicTag = Tag.of("topic", PaymentEventMetadataCatalog.CaptureRequestedMetadata.topic)

    private val processedCounter = Counter.builder("redis_retry_events_total")
        .description("Total retry events successfully re-published")
        .tag("result", "processed")
        .tags(listOf(topicTag))
        .register(meterRegistry)

    private val failedCounter = Counter.builder("redis_retry_events_total")
        .description("Total retry events that failed to re-publish")
        .tag("result", "failed")
        .tags(listOf(topicTag))
        .register(meterRegistry)

    private val batchTimer = Timer.builder("redis_retry_dispatch_batch_seconds")
        .description("Total time to dispatch a retry batch")
        .publishPercentiles(0.5, 0.95, 0.99)
        .tags(listOf(topicTag))
        .register(meterRegistry)

    // NOTE: with batch TX, we no longer time the broker round-trip per record.
    // We keep the same metric name for continuity, but now it measures per-record
    // "prepare & enqueue-into-batch" time, which still correlates with batch size/CPU.
    private val perEventTimer = Timer.builder("redis_retry_dispatch_event_seconds")
        .description("Time to prepare a single retry event for batch publish")
        .publishPercentiles(0.5, 0.95, 0.99)
        .tags(listOf(topicTag))
        .register(meterRegistry)

    init {
        Gauge.builder("redis_retry_batch_size") { batchSize.get().toDouble() }
            .description("Number of retry events processed in the last batch")
            .tags(listOf(topicTag))
            .register(meterRegistry)
    }

    @Scheduled(fixedDelay = 5_000)
    fun dispatch() {
        if (!running.compareAndSet(false, true)) {
            logger.warn("Previous dispatch still running, skipping this run")
            return
        }
        scheduler.execute {
            try { dispatchOnce() } finally { running.set(false) }
        }
    }

    fun dispatchOnce() {
        val batchSample = Timer.start(meterRegistry)
        var ok = 0
        var fail = 0

        val due = retryQueue.pollDueRetriesToInflight(pollLimit)
        batchSize.set(due.size)

        if (due.isEmpty()) {
            batchSample.stop(batchTimer)
            logger.debug("RetryDispatcher: nothing due right now")
            return
        }

        var idx = 0
        while (idx < due.size) {
            val end = min(idx + chunkSize, due.size)
            val chunk = due.subList(idx, end)
            val chunkCount = chunk.size

            try {
                // Build the envelopes list for ONE atomic TX
                val envs = ArrayList<EventEnvelope<CaptureRequested>>(chunkCount)
                for (item in chunk) {
                    val t = Timer.start(meterRegistry)
                    @Suppress("UNCHECKED_CAST")
                    val env = item.envelope as EventEnvelope<CaptureRequested>
                    envs.add(env)
                    t.stop(perEventTimer)
                }

                val futures = envs.map { publisher.publishAsync(it) }
                CompletableFuture.allOf(*futures.toTypedArray())
                    .get(30, SECONDS)

                // If get() succeeds, it's safe to remove from inflight
                for (item in chunk) retryQueue.removeFromInflight(item.raw)
                ok += chunkCount
            } catch (t: Throwable) {
                // any exception → treat as TX failed; keep inflight
                fail += chunkCount
                logger.warn(
                    "RetryDispatcher: chunk failed ({} items) – will be reclaimed. cause={}",
                    chunkCount, t.toString()
                )
            }

            idx = end
        }

        if (ok > 0) processedCounter.increment(ok.toDouble())
        if (fail > 0) failedCounter.increment(fail.toDouble())

        batchSample.stop(batchTimer)
        if(ok >0 || fail >0 || due.size>0) {
            logger.debug("RetryDispatcher: batch done ok={} fail={} polled={}", ok, fail, due.size)
        }
    }

    /** Requeue stale inflight items (e.g., if we crashed after popping) */
    @Scheduled(fixedDelay = 30_000)
    fun reclaimInflight() {
        val before = System.currentTimeMillis()
        retryQueue.reclaimInflight(olderThanMs = 60_000) // keep > worst-case TX time
        val took = System.currentTimeMillis() - before
        logger.debug("Reclaimed stale inflight ({} ms)", took)
    }
}

 */