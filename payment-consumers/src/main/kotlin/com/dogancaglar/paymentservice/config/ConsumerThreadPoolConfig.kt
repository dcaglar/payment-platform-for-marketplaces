package com.dogancaglar.paymentservice.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class ConsumerThreadPoolConfig {

    @Bean("pspExecutionPool")
    fun pspExecutionPool(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 50
            maxPoolSize = 500
            queueCapacity = 1000
            setThreadNamePrefix("psp-")
            initialize()
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
            setThreadNamePrefix("payment-consumers-spring-scheduled-")
            setWaitForTasksToCompleteOnShutdown(true)
            initialize()
        }

    @Bean("retryDispatcherSpringScheduler")
    fun retryDispatcherScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("retry-dispatcher-")
            setWaitForTasksToCompleteOnShutdown(true)
            initialize()
        }
}
