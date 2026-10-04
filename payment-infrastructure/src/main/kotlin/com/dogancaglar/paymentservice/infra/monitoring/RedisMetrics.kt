package com.dogancaglar.paymentservice.infra.monitoring

object RedisMetrics {
    const val RETRY_QUEUE_SIZE = "redis_retry_zset_size"
    const val RETRY_DISPATCH_EXECUTION_SECONDS = "redis_retry_dispatch_execution_seconds"

    // For domain-specific queues, use functions:
    fun statusCheckBufferSize() = "redis_status_check_zset_size"
    fun delayedStatusCheckExecution() = "redis_status_check_dispatch_execution_seconds"
}
