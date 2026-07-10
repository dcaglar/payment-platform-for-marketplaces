package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.ports.outbound.CentralOutboxRelayPort
import io.opentelemetry.instrumentation.annotations.WithSpan
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class CentralOutboxDispatchWorker(
    private val centralOutboxRepository: CentralOutboxRelayPort,
    private val kafkaHelper: CentralOutboxKafkaHelper,
    @Qualifier("resilientExecutor") private val executor: ThreadPoolTaskExecutor,
    @Value("\${outbox-relay.batch-size:500}") private val batchSize: Int,
    @Value("\${app.instance-id:central-relay}") private val appInstanceId: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @WithSpan("central-outbox-relay-batch-worker")
    fun centralOutboxRelayBatchWorker() {
        val tSafe = centralOutboxRepository.computeTSafe() ?: Utc.nowInstant()
        val workerId = "$appInstanceId-poll-${UUID.randomUUID()}"
        
        val batch = centralOutboxRepository.findEligible(tSafe, batchSize, workerId)
        if (batch.isEmpty()) {
            return
        }

        logger.debug("Fetched {} eligible events (T_safe = {}, workerId = {})", batch.size, tSafe, workerId)

        val groups = batch.groupBy { it.aggregateId }

        for ((aggregateId, events) in groups) {
            executor.execute {
                runBlocking {
                    for (entry in events) {
                        try {
                            kafkaHelper.processEntryAsync(entry).await()

                            logger.debug("✅ Marked dispatched outboxevent with ${entry.eventType} and oeid ${entry.oeid}")
                            markDispatched(entry.oeid, Utc.toInstant(entry.createdAt))

                        } catch (exception: Exception) {
                            logger.error("🛑 Breaking chain for aggregate $aggregateId at oeid=${entry.oeid}", exception)
                            unclaimSpecific(entry.oeid, Utc.toInstant(entry.createdAt), workerId)
                            break
                        }
                    }
                }
            }
        }
    }

    fun reclaimStuck(): Int {
        val reclaimed = centralOutboxRepository.reclaimStuck(staleSeconds = 120)
        if (reclaimed > 0) {
            logger.warn("⚠️ Reclaimed {} stuck PROCESSING events back to NEW", reclaimed)
        }
        return reclaimed
    }

    private fun markDispatched(oeid: Long, createdAt: Instant) {
        centralOutboxRepository.markDispatched(oeid, createdAt)
    }

    private fun unclaimSpecific(oeid: Long, createdAt: Instant, workerId: String) {
        try {
            centralOutboxRepository.unclaimSpecific(oeid, createdAt, workerId)
        } catch (dbEx: Exception) {
            logger.error("Failed to unclaim oeid=$oeid. Relies on reclaimer.", dbEx)
        }
    }
}
