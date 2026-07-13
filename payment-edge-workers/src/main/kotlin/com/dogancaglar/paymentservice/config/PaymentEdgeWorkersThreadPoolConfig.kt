package com.dogancaglar.paymentservice.config

import io.opentelemetry.context.Context
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class PaymentEdgeWorkersThreadPoolConfig {
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
        return scheduler
    }


    @Bean("resilientExecutor")
    fun resilientExecutor(): ThreadPoolTaskExecutor {

        val resilientExecutor = ThreadPoolTaskExecutor()
        resilientExecutor.corePoolSize = 32
        resilientExecutor.maxPoolSize = 32
        resilientExecutor.queueCapacity = 500
        resilientExecutor.setThreadNamePrefix("edge-worker-resilient-callback-")
        resilientExecutor.setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
/*
public ExecutorService wrapExecutor(ExecutorService executor) {
  return Context.taskWrapping(executor);
}
 */
        Context.taskWrapping {  }
        resilientExecutor.setTaskDecorator { runnable ->
            val currentContext = Context.current()
            Runnable { currentContext.makeCurrent().use { runnable.run() } }
        }

        return resilientExecutor
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
