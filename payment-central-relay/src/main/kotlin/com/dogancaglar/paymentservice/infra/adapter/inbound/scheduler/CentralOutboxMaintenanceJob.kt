package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.time.Utc
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
import io.opentelemetry.context.Context


import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import com.dogancaglar.common.db.partitioning.AbstractOutboxPartitionCreator

@Component
class CentralOutboxMaintenanceJob(
    @Qualifier("maintenanceJdbcTemplate") jdbcTemplate: JdbcTemplate,
    @param:Qualifier("centralOutboxEventPartitionMaintenanceScheduler") private val taskScheduler: ThreadPoolTaskScheduler,
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
            try {
                val start = Utc.nowLocalDateTime()
                ensureCurrentAndNext()
                val end = Utc.nowLocalDateTime()
                val durationMs = ChronoUnit.MILLIS.between(start, end)
                logger.debug("Central partition check complete started at $start, ended at $end, duration: $durationMs ")
            } catch (t: Throwable) {
                maintenanceErrorCounter.add(1, Attributes.of(AttributeKey.stringKey("job"), "CentralOutboxMaintenanceJob.ensureCurrentAndNext"))
                throw t
            }
        }
    }

    @Scheduled(initialDelay = 45000, fixedDelay = 21 * 60 * 1000)
    fun pruneOldPartitionsScheduled() {
        taskScheduler.execute {
            try {
                val start = Utc.nowLocalDateTime()
                pruneOldPartitions()
                val end = Utc.nowLocalDateTime()
                val durationMs = ChronoUnit.MILLIS.between(start, end)
                logger.debug("Central partition prune complete started at $start, ended at $end, duration: $durationMs ")
            } catch (t: Throwable) {
                maintenanceErrorCounter.add(1, Attributes.of(AttributeKey.stringKey("job"), "CentralOutboxMaintenanceJob.pruneOldPartitions"))
                throw t
            }
        }
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 15 * 60 * 1000)
    fun vacuumOldPartitionsWithNewRowsScheduled() {
        taskScheduler.execute {
            try {
                val start = Utc.nowLocalDateTime()
                vacuumOldPartitionsWithNewRows()
                val end = Utc.nowLocalDateTime()
                val durationMs = ChronoUnit.MILLIS.between(start, end)
                logger.debug("Central partition vacuum check complete started at $start, ended at $end, duration: $durationMs ")
            } catch (t: Throwable) {
                maintenanceErrorCounter.add(1, Attributes.of(AttributeKey.stringKey("job"), "CentralOutboxMaintenanceJob.vacuumOldPartitionsWithNewRows"))
                throw t
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
