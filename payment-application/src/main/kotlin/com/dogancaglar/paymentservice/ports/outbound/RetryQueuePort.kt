package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.common.event.Event
import com.dogancaglar.paymentservice.application.util.RetryItem

interface RetryQueuePort<T : Event> {
    fun scheduleRetry(
        event: T,
        backOffMillis: Long
    )

    fun getRetryCount(identifier: String): Int
    fun resetRetryCounter(identifier: String)
    fun pollDueRetriesToInflight(maxBatchSize: Long): List<RetryItem>
}
