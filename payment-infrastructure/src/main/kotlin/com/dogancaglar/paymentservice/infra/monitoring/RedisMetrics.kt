package com.dogancaglar.paymentservice.infra.monitoring

object RedisMetrics {
    const val RETRY_QUEUE_SIZE = "redis_retry_zset_size"
    const val RETRY_DISPATCH_EXECUTION_SECONDS = "redis_retry_dispatch_execution_seconds"

    const val STATUS_CHECK_BUFFER_SIZE = "redis_status_check_zset_size"
    const val DELAYED_STATUS_CHECK_EXECUTION_SECONDS = "redis_status_check_dispatch_execution_seconds"
}
