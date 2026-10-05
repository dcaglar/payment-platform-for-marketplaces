package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.db.partitioning.AbstractOutboxPartitionCreator
import com.dogancaglar.common.time.Utc
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.time.temporal.ChronoUnit

@Component
class CentralOutboxMaintenanceJob(
    @Qualifier("maintenanceJdbcTemplate") jdbcTemplate: JdbcTemplate,
    @param:Qualifier(
        "centralOutboxEventPartitionMaintenanceScheduler"
    ) private val taskScheduler: ThreadPoolTaskScheduler,
    openTelemetry: OpenTelemetry
) : AbstractOutboxPartitionCreator(jdbcTemplate) {

    private val meter = openTelemetry.meterBuilder("payment-central-relay.maintenance").build()
    private val maintenanceErrorCounter = meter.counterBuilder("maintenance_job_error_total").build()

    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(
        initialDelay = 0,
        fixedDelayString = "\${outbox-partition.fixed-delay:PT10M}"
    )
    fun ensureCurrentAndNextScheduled() {
        taskScheduler.execute {
            countFailures("CentralOutboxMaintenanceJob.ensureCurrentAndNext") {
                val start = Utc.nowLocalDateTime()
                ensureCurrentAndNext()
                val end = Utc.nowLocalDateTime()
                val durationMs = ChronoUnit.MILLIS.between(start, end)
                logger.debug(
                    "Central partition check complete started at $start, ended at $end, duration: $durationMs "
                )
            }
        }
    }

    @Scheduled(initialDelay = 45000, fixedDelay = 21 * 60 * 1000)
    fun pruneOldPartitionsScheduled() {
        taskScheduler.execute {
            countFailures("CentralOutboxMaintenanceJob.pruneOldPartitions") {
                val start = Utc.nowLocalDateTime()
                pruneOldPartitions()
                val end = Utc.nowLocalDateTime()
                val durationMs = ChronoUnit.MILLIS.between(start, end)
                logger.debug(
                    "Central partition prune complete started at $start, ended at $end, duration: $durationMs "
                )
            }
        }
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 15 * 60 * 1000)
    fun vacuumOldPartitionsWithNewRowsScheduled() {
        taskScheduler.execute {
            countFailures("CentralOutboxMaintenanceJob.vacuumOldPartitionsWithNewRows") {
                val start = Utc.nowLocalDateTime()
                vacuumOldPartitionsWithNewRows()
                val end = Utc.nowLocalDateTime()
                val durationMs = ChronoUnit.MILLIS.between(start, end)
                logger.debug(
                    "Central partition vacuum check complete started at $start, ended at $end, duration: $durationMs "
                )
            }
        }
    }

    /** Runs a maintenance job; a failure is counted (maintenance_job_error_total) and propagates. No catch needed. */
    private fun countFailures(job: String, run: () -> Unit) {
        var completed = false
        try {
            run()
            completed = true
        } finally {
            if (!completed) {
                maintenanceErrorCounter.add(1, Attributes.of(AttributeKey.stringKey("job"), job))
            }
        }
    }
}

@Configuration
class CentralOutboxPartitionCreatorConfig {
    @Bean("centralOutboxEventPartitionMaintenanceScheduler")
    fun centralOutboxEventPartitionMaintenanceScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.setThreadNamePrefix("central-outbox-maintenance-pool-")
        scheduler.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }
        return scheduler
    }
}
