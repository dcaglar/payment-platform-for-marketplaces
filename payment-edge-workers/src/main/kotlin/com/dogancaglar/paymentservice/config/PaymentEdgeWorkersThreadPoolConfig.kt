package com.dogancaglar.paymentservice.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class PaymentEdgeWorkersThreadPoolConfig(private val meterRegistry: MeterRegistry) {
    @Bean("outboxJobTaskScheduler")
    fun outboxTaskScheduler(
        @Value("\${outbox-dispatcher.pool-size:2}") poolSize: Int,
    ): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = poolSize
        scheduler.setThreadNamePrefix("outbox-dispatcher-pool-")
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        // Metrics with a unique tag!
        meterRegistry.gauge(
            "scheduler_outbox_active_threads",
            listOf(Tag.of("name", "outbox-dispatch")),
            scheduler
        ) { it.activeCount.toDouble() }

        meterRegistry.gauge(
            "scheduler_outbox_pool_size_threads",
            listOf(Tag.of("name", "outbox-dispatch")),
            scheduler
        ) { it.poolSize.toDouble() }

        meterRegistry.gauge(
            "scheduler_outbox_queue_size",
            listOf(Tag.of("name", "outbox-dispatch")),
            scheduler
        ) { it.scheduledThreadPoolExecutor.queue.size.toDouble() }

        return scheduler
    }

    @Bean
    fun outboxEventPartitionMaintenanceScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 2
        scheduler.setThreadNamePrefix("outbox-mainenance-pool-")
        scheduler.initialize()
        return scheduler
    }

    @Bean("resilientExecutor")
    fun resilientExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 32
            maxPoolSize = 32
            queueCapacity = 500
            setThreadNamePrefix("resilient-callback-")
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
            initialize()
        }

    @Bean("taskScheduler")
    fun defaultSpringScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 2
            setThreadNamePrefix("payment-service-spring-scheduled-")
            setWaitForTasksToCompleteOnShutdown(true)
            initialize()
        }
}
