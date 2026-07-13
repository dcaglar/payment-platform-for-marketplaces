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
        pspExecutor.corePoolSize = 50
        pspExecutor.maxPoolSize = 500
        pspExecutor.queueCapacity = 1000
        pspExecutor.setThreadNamePrefix("psp-")
        pspExecutor.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }
        return  pspExecutor
    }

    @Bean("resilientExecutor")
    fun resilientExecutor(): ThreadPoolTaskExecutor {

        val resilientExecutor = ThreadPoolTaskExecutor()
        resilientExecutor.corePoolSize = 32
        resilientExecutor.maxPoolSize = 32
        resilientExecutor.queueCapacity = 500
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
}
