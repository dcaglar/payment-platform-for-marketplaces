package com.dogancaglar.paymentservice.config

import io.opentelemetry.context.Context
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.ThreadPoolExecutor
import kotlin.use

@Configuration
class ConsumerThreadPoolConfig {

    @Bean("pspExecutionPool")
    fun pspExecutionPool(): ThreadPoolTaskExecutor {
        val pspExecutor = ThreadPoolTaskExecutor()
        pspExecutor.corePoolSize = PSP_CORE_POOL_SIZE
        pspExecutor.maxPoolSize = PSP_MAX_POOL_SIZE
        pspExecutor.queueCapacity = PSP_QUEUE_CAPACITY
        pspExecutor.setThreadNamePrefix("psp-")
        pspExecutor.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }
        return pspExecutor
    }

    @Bean("resilientExecutor")
    fun resilientExecutor(): ThreadPoolTaskExecutor {
        val resilientExecutor = ThreadPoolTaskExecutor()
        resilientExecutor.corePoolSize = RESILIENT_POOL_SIZE
        resilientExecutor.maxPoolSize = RESILIENT_POOL_SIZE
        resilientExecutor.queueCapacity = RESILIENT_QUEUE_CAPACITY
        resilientExecutor.setThreadNamePrefix("consumers-resilient-callback-")
        resilientExecutor.setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())

        resilientExecutor.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }

        return resilientExecutor
    }

    @Bean("taskScheduler")
    fun defaultSpringScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 2
        scheduler.setThreadNamePrefix("payment-consumers-spring-scheduled-")
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        scheduler.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }

        return scheduler
    }

    @Bean("retryDispatcherSpringScheduler")
    fun retryDispatcherScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.setThreadNamePrefix("retry-dispatcher-")
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        scheduler.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }

        return scheduler
    }

    private companion object {
        const val PSP_CORE_POOL_SIZE = 50
        const val PSP_MAX_POOL_SIZE = 500
        const val PSP_QUEUE_CAPACITY = 1000
        const val RESILIENT_POOL_SIZE = 32
        const val RESILIENT_QUEUE_CAPACITY = 500
    }
}
