package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.ports.outbound.CentralOutboxForwarderPort
import com.dogancaglar.paymentservice.ports.outbound.LocalOutboxStoreAndForwardPort
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import jakarta.annotation.PostConstruct

@Component
class LocalOutboxDispatchWorker(
    @param:Qualifier("localOutboxStoreAndForwardPort") private val localOutboxStoreAndForwardPort: LocalOutboxStoreAndForwardPort,
    private val centralOutboxRepository: CentralOutboxForwarderPort,
    @param:Value("\${app.instance-id}") private val appInstanceId: String,
    @param:Value("\${outbox-dispatcher.batch-size:250}") private val batchSize: Int,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun registerMetrics() {
        io.micrometer.core.instrument.Gauge.builder("local_outbox_backlog_size", this) {
            localOutboxStoreAndForwardPort.countNew().toDouble()
        }.register(meterRegistry)
    }

    fun isSchemaReady(): Boolean = centralOutboxRepository.isSchemaReady()

    fun deleteWatermark(appInstanceId: String) = centralOutboxRepository.deleteWatermark(appInstanceId)

    @Transactional(transactionManager = "outboxTxManager", timeout = 5)
    fun reclaimStuck(): Int {
        return localOutboxStoreAndForwardPort.reclaimStuck(60 * 10)
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
        } catch (t: Throwable) {
            logger.warn("⚠️ Batch forward failed; will unclaim {} rows.", events.size, t)
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
        val sample = Timer.start(meterRegistry)
        val threadName = Thread.currentThread().name
        val workerId = "$appInstanceId:$threadName"

        try {
            val events = claimBatch(batchSize, workerId)
            if (events.isEmpty()) {
                centralOutboxRepository.updateWatermark(appInstanceId, Utc.nowInstant())
                sample.stop(meterRegistry.timer("outbox_dispatcher_duration"))
                return
            }

            val success = forwardBatch(events)
            if (success) {
                persistResults(events.map { it.markAsSent() })
                logger.info("Forwarded ok={} on {}", events.size, threadName)
                meterRegistry.counter("outbox_dispatched_total").increment(events.size.toDouble())
            } else {
                try {
                    unclaimFailedNow(workerId, events)
                } catch (t: Throwable) {
                    logger.warn("Unclaim failed for {} rows (worker={}) – will rely on reclaimer", events.size, workerId, t)
                }
                logger.info("Forwarded failed={} on {}", events.size, threadName)
                meterRegistry.counter("outbox_dispatch_failed_total").increment(events.size.toDouble())
            }
        } catch (t: Throwable) {
            meterRegistry.counter("outbox_dispatch_failed_total").increment()
            throw t
        } finally {
            sample.stop(meterRegistry.timer("outbox_dispatcher_duration"))
        }
    }

    @WithSpan("outbox-shutdown-flush")
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
}
