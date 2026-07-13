package com.dogancaglar.paymentservice.config

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class PaymentServiceThreadPoolConfig(private val openTelemetry: OpenTelemetry) {

    @Bean("outboxJobTaskScheduler")
    fun outboxTaskScheduler(
        @Value("\${outbox-dispatcher.pool-size:2}") poolSize: Int,
    ): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = poolSize
        scheduler.setThreadNamePrefix("outbox-dispatcher-pool-")
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        scheduler.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }

        val meter = openTelemetry.meterBuilder("payment-service.threadpool").build()
        val attributes = Attributes.of(AttributeKey.stringKey("name"), "outbox-dispatch")

        meter.gaugeBuilder("scheduler_outbox_active_threads")
            .ofLongs()
            .buildWithCallback { it.record(scheduler.activeCount.toLong(), attributes) }

        meter.gaugeBuilder("scheduler_outbox_pool_size_threads")
            .ofLongs()
            .buildWithCallback { it.record(scheduler.poolSize.toLong(), attributes) }

        meter.gaugeBuilder("scheduler_outbox_queue_size")
            .ofLongs()
            .buildWithCallback { it.record(scheduler.scheduledThreadPoolExecutor.queue.size.toLong(), attributes) }

        return scheduler
    }

    @Bean("createPaymentIntentExecutor")
    fun createPaymentIntentExecutor(): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 250
        executor.maxPoolSize = 250
        executor.queueCapacity = 50
        executor.setThreadNamePrefix("po-psp-")
        executor.setRejectedExecutionHandler(ThreadPoolExecutor.DiscardPolicy())
        executor.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }

        return executor
    }

    @Bean("authorizePaymentIntentExecutor")
    fun authorizePaymentIntentExecutor(): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 80
        executor.maxPoolSize = 200
        executor.queueCapacity = 50
        executor.setThreadNamePrefix("po-psp-")
        executor.setRejectedExecutionHandler(ThreadPoolExecutor.DiscardPolicy())
        executor.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }

        return executor
    }

    @Bean
    fun outboxEventPartitionMaintenanceScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 2
        scheduler.setThreadNamePrefix("outbox-mainenance-pool-")
        scheduler.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }

        return scheduler
    }

    @Bean("resilientExecutor")
    fun resilientExecutor(): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 200
        executor.maxPoolSize = 200
        executor.queueCapacity = 500
        executor.setThreadNamePrefix("resilient-callback-")
        executor.setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        executor.setTaskDecorator { runnable ->
            val currentContext = Context.current()

            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }

        return executor
    }

    @Bean("taskScheduler")
    fun defaultSpringScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 2
        scheduler.setThreadNamePrefix("payment-service-spring-scheduled-")
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        scheduler.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }

        return scheduler
    }
}
