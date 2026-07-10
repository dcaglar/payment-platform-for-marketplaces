package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.time.Utc
import com.dogancaglar.common.db.partitioning.AbstractOutboxPartitionCreator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.time.temporal.ChronoUnit
import io.opentelemetry.context.Context


@Component
class LocalOutboxMaintenanceJob(
    @param:Qualifier("maintenanceJdbcTemplate") jdbcTemplate: JdbcTemplate,
    private val meterRegistry: io.micrometer.core.instrument.MeterRegistry
) : AbstractOutboxPartitionCreator(jdbcTemplate) {

    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(
        initialDelay = 0,
        fixedDelayString = "\${outbox-partition.fixed-delay:PT10M}"
    )
    @Async("partitionCreationExecutor")
    fun ensureCurrentAndNextScheduled() {
        try {
            waitForParentTable()

            val start = Utc.nowLocalDateTime()
            ensureCurrentAndNext()
            val end = Utc.nowLocalDateTime()
            val durationMs = ChronoUnit.MILLIS.between(start, end)
            logger.debug("Partition check complete started at $start, ended at $end, duration: $durationMs ")
        } catch (t: Throwable) {
            meterRegistry.counter("maintenance_job_error_total", "job", "LocalOutboxMaintenanceJob.ensureCurrentAndNext").increment()
            throw t
        }
    }

    private fun waitForParentTable() {
        var attempts = 0
        while (attempts < 20) {
            try {
                val exists = jdbcTemplate.queryForObject(
                    "SELECT count(1) FROM pg_tables WHERE tablename = 'outbox_event'",
                    Int::class.java
                ) ?: 0
                if (exists > 0) {
                    logger.info("Found outbox_event table! Proceeding with partition creation.")
                    return
                }
            } catch (e: Exception) {
                // ignore and retry
            }
            attempts++
            logger.info("Waiting for payment-service to create outbox_event table via Liquibase... (Attempt ${'$'}attempts/20)")
            Thread.sleep(3000)
        }
        logger.error("Timed out waiting for outbox_event table to be created!")
    }

    @Scheduled(initialDelay = 45000, fixedDelay = 21 * 60 * 1000)
    @Async("partitionRemovalExecutor")
    fun pruneOldPartitionsScheduled() {
        try {
            val start = Utc.nowLocalDateTime()
            pruneOldPartitions()
            val end = Utc.nowLocalDateTime()
            val durationMs = ChronoUnit.MILLIS.between(start, end)
            logger.debug("Partition prune complete started at $start, ended at $end, duration: $durationMs ")
        } catch (t: Throwable) {
            meterRegistry.counter("maintenance_job_error_total", "job", "LocalOutboxMaintenanceJob.pruneOldPartitions").increment()
            throw t
        }
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 15 * 60 * 1000)
    @Async("partitionRemovalExecutor")
    fun vacuumOldPartitionsWithNewRowsScheduled() {
        try {
            val start = Utc.nowLocalDateTime()
            vacuumOldPartitionsWithNewRows()
            val end = Utc.nowLocalDateTime()
            val durationMs = ChronoUnit.MILLIS.between(start, end)
            logger.debug("Partition vacuum check complete started at $start, ended at $end, duration: $durationMs ")
        } catch (t: Throwable) {
            meterRegistry.counter("maintenance_job_error_total", "job", "LocalOutboxMaintenanceJob.vacuumOldPartitionsWithNewRows").increment()
            throw t
        }
    }
}

@Configuration
class AsyncConfig {
    @Bean("partitionCreationExecutor")
    fun partitionCreationExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 2
        setThreadNamePrefix("partition-creation-")
        setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }

    }

    @Bean("partitionRemovalExecutor")
    fun partitionRemovalExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 2
        setThreadNamePrefix("partition-removal-")
        setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }

    }
}
