package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.time.Utc
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Service

/**
 * LocalOutboxStoreAndForwardJob - The Edge Local Forwarder Scheduler.
 *
 * Handles timers, thread pools, and graceful shutdown lifecycle for edge-to-central event forwarding.
 * Delegates actual transactional work to the injected LocalOutboxDispatchWorker.
 */
@Service
@DependsOn("localOutboxMaintenanceJob")
class LocalOutboxStoreAndForwardJob(
    private val dispatchWorker: LocalOutboxDispatchWorker,
    @param:Qualifier("outboxJobTaskScheduler") private val taskScheduler: ThreadPoolTaskScheduler,
    @param:Value("\${outbox-dispatcher.thread-count:2}") private val threadCount: Int,
    @param:Value("\${app.instance-id}") private val appInstanceId: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @WithSpan("myScheduledMethod")
    @Scheduled(initialDelay = 30000, fixedDelay = 500)
    fun dispatchBatches() {
        if (dispatchWorker.isSchemaReady()) {
            repeat(threadCount) { workerIdx ->
                val delayMs = START_DELAY_PER_WORKER_MS * workerIdx
                taskScheduler.schedule(
                    {
                        dispatchWorker.dispatchBatchWorker()
                    },
                    Utc.nowInstant().plusMillis(delayMs)
                )
            }
        } else {
            logger.warn("FATAL ERROR, CENTRAL TABLE NOT PRESENT, SHUTTING DOWN")
        }
    }

    @Scheduled(initialDelay = 30000, fixedDelay = 120000)
    fun reclaimStuck() {
        val reclaimed = dispatchWorker.reclaimStuck()
        if (reclaimed > 0) {
            logger.warn("Reclaimer reset {} stuck outbox events to NEW", reclaimed)
        }
    }

    @jakarta.annotation.PreDestroy
    fun onShutdown() {
        logger.info(
            "Step 1: Graceful shutdown initiated. We will block termination until the local outbox is completely empty."
        )

        // Forcefully reclaim any events that were CLAIMED by threads that just got interrupted
        val reclaimed = dispatchWorker.reclaimAll()
        if (reclaimed > 0) {
            logger.info(
                "Step 2: Rescued {} abandoned events. (Active threads were killed, so we instantly reset their " +
                    "events back to 'NEW')",
                reclaimed
            )
        } else {
            logger.info("Step 2: No abandoned events needed rescue.")
        }

        logger.info(
            "Step 3: Beginning the final drain loop. We will wait for 3 consecutive seconds of silence to ensure no " +
                "last-minute events are missed."
        )
        var flushCount = 0
        var emptyCycles = 0
        while (emptyCycles < EMPTY_CYCLES_BEFORE_DONE) {
            val workerId = "$appInstanceId:shutdown-flush"
            val processed = dispatchWorker.flushBatch(workerId)

            if (processed == 0) {
                emptyCycles++
                logger.info("   -> Empty cycle {}/3: No new events found in the local outbox.", emptyCycles)
                Thread.sleep(EMPTY_CYCLE_PAUSE_MS)
            } else if (processed > 0) {
                logger.info("   -> Sent {} events! Resetting empty cycle countdown back to 0.", processed)
                emptyCycles = 0
                flushCount += processed
            } else {
                // processed == -1 means flush failed and it unclaimed
                logger.warn("   -> Batch forward failed during drain. Backing off 2 seconds before retry.")
                Thread.sleep(FAILED_BATCH_BACKOFF_MS)
            }
        }

        logger.info(
            "Step 4: Drain complete! We saw 3 full seconds of silence. Successfully forwarded a total of {} final " +
                "events.",
            flushCount
        )
        try {
            dispatchWorker.deleteWatermark(appInstanceId)
            logger.info(
                "Step 5: Deleted worker watermark for {}. Pod is now safely cleared to terminate.",
                appInstanceId
            )
        } catch (e: DataAccessException) {
            // shutdown must go on; a stale watermark only delays nothing (the pod is going away)
            logger.error("Failed to delete watermark during shutdown!", e)
        }
    }

    private companion object {
        const val START_DELAY_PER_WORKER_MS = 500L
        const val EMPTY_CYCLES_BEFORE_DONE = 3
        const val EMPTY_CYCLE_PAUSE_MS = 1000L
        const val FAILED_BATCH_BACKOFF_MS = 2000L
    }
}
