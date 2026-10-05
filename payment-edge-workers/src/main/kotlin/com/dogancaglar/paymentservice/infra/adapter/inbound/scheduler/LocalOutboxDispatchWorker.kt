package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.ports.outbound.CentralOutboxForwarderPort
import com.dogancaglar.paymentservice.ports.outbound.LocalOutboxStoreAndForwardPort
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.annotations.WithSpan
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class LocalOutboxDispatchWorker(
    @param:Qualifier(
        "localOutboxStoreAndForwardPort"
    ) private val localOutboxStoreAndForwardPort: LocalOutboxStoreAndForwardPort,
    private val centralOutboxRepository: CentralOutboxForwarderPort,
    @param:Value("\${app.instance-id}") private val appInstanceId: String,
    @param:Value("\${outbox-dispatcher.batch-size:250}") private val batchSize: Int,
    private val openTelemetry: OpenTelemetry
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val meter = openTelemetry.meterBuilder("payment-edge-workers.local.outbox").build()
    private val dispatcherDuration = meter.histogramBuilder("outbox_dispatcher_duration").setUnit("s").build()
    private val dispatchedTotal = meter.counterBuilder("outbox_dispatched_total").build()
    private val dispatchFailedTotal = meter.counterBuilder("outbox_dispatch_failed_total").build()

    @PostConstruct
    fun registerMetrics() {
        meter.gaugeBuilder("local_outbox_backlog_size")
            .ofLongs()
            .buildWithCallback { it.record(localOutboxStoreAndForwardPort.countNew().toLong()) }
    }

    fun isSchemaReady(): Boolean = centralOutboxRepository.isSchemaReady()

    fun deleteWatermark(appInstanceId: String) = centralOutboxRepository.deleteWatermark(appInstanceId)

    @Transactional(transactionManager = "outboxTxManager", timeout = 5)
    fun reclaimStuck(): Int {
        return localOutboxStoreAndForwardPort.reclaimStuck(STUCK_AFTER_SECONDS)
    }

    @Transactional(transactionManager = "outboxTxManager", timeout = 5)
    fun reclaimAll(): Int {
        return localOutboxStoreAndForwardPort.reclaimStuck(0)
    }

    @Transactional(transactionManager = "outboxTxManager", timeout = 2)
    fun claimBatch(batchSize: Int, workerId: String): List<OutboxEvent> {
        return localOutboxStoreAndForwardPort.findEligible(batchSize, workerId)
    }

    @Transactional(transactionManager = "centralTxManager", timeout = 5)
    fun forwardBatch(events: List<OutboxEvent>): Boolean {
        if (events.isEmpty()) return true

        return try {
            centralOutboxRepository.insertBatch(appInstanceId, events)
            val maxOriginatedAt = events.maxOf { Utc.toInstant(it.createdAt) }
            centralOutboxRepository.updateWatermark(appInstanceId, maxOriginatedAt)
            true
        } catch (e: DataAccessException) {
            logger.warn("⚠️ Batch forward failed; will unclaim {} rows.", events.size, e)
            false
        }
    }

    @Transactional(transactionManager = "outboxTxManager", timeout = 5)
    fun persistResults(succeeded: List<OutboxEvent>) {
        if (succeeded.isNotEmpty()) {
            localOutboxStoreAndForwardPort.markDispatched(succeeded)
        }
    }

    @Transactional(transactionManager = "outboxTxManager", timeout = 2)
    fun unclaimFailedNow(workerId: String, failed: List<OutboxEvent>) {
        if (failed.isEmpty()) return
        val n = localOutboxStoreAndForwardPort.unclaimFailed(workerId, failed.map { it.oeid })
        if (n > 0) {
            logger.warn("Unclaimed {} failed outbox rows for worker={}", n, workerId)
        }
    }

    @WithSpan("outbox-dispatch-worker")
    fun dispatchBatchWorker() {
        val startNs = System.nanoTime()
        val threadName = Thread.currentThread().name
        val workerId = "$appInstanceId:$threadName"

        // counted as failed unless the run gets to the end (no catch: the error itself propagates)
        var completed = false
        try {
            val events = claimBatch(batchSize, workerId)
            if (events.isEmpty()) {
                centralOutboxRepository.updateWatermark(appInstanceId, Utc.nowInstant())
                completed = true
                return
            }

            val success = forwardBatch(events)
            if (success) {
                persistResults(events.map { it.markAsSent() })
                logger.info("Forwarded ok={} on {}", events.size, threadName)
                dispatchedTotal.add(events.size.toLong())
            } else {
                try {
                    unclaimFailedNow(workerId, events)
                } catch (e: DataAccessException) {
                    logger.warn(
                        "Unclaim failed for {} rows (worker={}) – will rely on reclaimer",
                        events.size,
                        workerId,
                        e
                    )
                }
                logger.info("Forwarded failed={} on {}", events.size, threadName)
                dispatchFailedTotal.add(events.size.toLong())
            }
            completed = true
        } finally {
            if (!completed) {
                dispatchFailedTotal.add(1)
            }
            dispatcherDuration.record((System.nanoTime() - startNs) / NANOS_PER_SECOND)
        }
    }

    @WithSpan("outbox-shutdown-flush")
    @Suppress("ReturnCount") // guard clauses
    fun flushBatch(workerId: String): Int {
        val events = claimBatch(batchSize, workerId)
        if (events.isEmpty()) {
            return 0
        }
        val success = forwardBatch(events)
        if (success) {
            persistResults(events.map { it.markAsSent() })
            return events.size
        } else {
            logger.warn("Shutdown flush failed. Will retry.")
            unclaimFailedNow(workerId, events)
            return -1
        }
    }

    private companion object {
        const val STUCK_AFTER_SECONDS = 600 // 10 minutes in PROCESSING: reclaimed
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
