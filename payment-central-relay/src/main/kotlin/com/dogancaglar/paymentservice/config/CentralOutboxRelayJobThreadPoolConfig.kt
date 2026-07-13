package com.dogancaglar.paymentservice.config

import io.opentelemetry.context.Context
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class CentralOutboxRelayJobThreadPoolConfig {


/*
val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = poolSize
        scheduler.setThreadNamePrefix("outbox-dispatcher-pool-")
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        scheduler.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }
 */
    @Bean("resilientExecutor")
    fun resilientExecutor(): ThreadPoolTaskExecutor {

    val resilientExecutor = ThreadPoolTaskExecutor()
    resilientExecutor.corePoolSize = 32
    resilientExecutor.maxPoolSize = 32
    resilientExecutor.queueCapacity = 500
    resilientExecutor.setThreadNamePrefix("resilient-callback-")
    resilientExecutor.setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())

    resilientExecutor.setTaskDecorator { runnable ->
        val currentContext = Context.current()
        Runnable { currentContext.makeCurrent().use { runnable.run() } }
    }

    return resilientExecutor
}

    @Bean("taskScheduler")
    fun defaultSpringScheduler(): ThreadPoolTaskScheduler {
        val myTaskScheduler = ThreadPoolTaskScheduler()
        myTaskScheduler.poolSize = 2
        myTaskScheduler.setThreadNamePrefix("central-outbox-relay-spring-scheduled-")
        myTaskScheduler.setWaitForTasksToCompleteOnShutdown(true)
        myTaskScheduler.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }
        return  myTaskScheduler
    }

    @Bean("retryDispatcherSpringScheduler")
    fun retryDispatcherScheduler(): ThreadPoolTaskScheduler {
        val retryPoolTaskScheduler = ThreadPoolTaskScheduler()
        retryPoolTaskScheduler.poolSize = 1
        retryPoolTaskScheduler.setThreadNamePrefix("retry-dispatcher-")
        retryPoolTaskScheduler.setWaitForTasksToCompleteOnShutdown(true)
        retryPoolTaskScheduler.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }
        return  retryPoolTaskScheduler
    }
}
