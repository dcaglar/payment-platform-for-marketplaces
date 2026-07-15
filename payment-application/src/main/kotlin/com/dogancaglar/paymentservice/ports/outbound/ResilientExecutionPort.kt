package com.dogancaglar.paymentservice.ports.outbound

import java.util.concurrent.CompletableFuture

interface ResilientExecutionPort {
    /**
     * Executes a task with a timeout. If the task completes within the timeout, the result is returned.
     * If it times out, the fallback is returned immediately, and the task continues in the background.
     *
     * @param primaryTask The async task to execute (e.g. external API call)
     * @param timeoutMs The maximum time to wait synchronously
     * @param onTimeoutFallback Returns the value to be returned to the caller immediately upon timeout
     * @param onBackgroundSuccess Callback executed if the task completes successfully in the background (after timeout)
     * @param onBackgroundFailure Callback executed if the task fails in the background (after timeout)
     * @return The result of the task (if fast) or the fallback value (if slow)
     */
    fun <T> executeWithTimeoutAndBackgroundFallback(
        primaryTask: () -> CompletableFuture<T>,
        timeoutMs: Long,
        onTimeoutFallback: () -> T,
        onBackgroundSuccess: (T) -> Unit,
        onBackgroundFailure: (Throwable) -> Unit
    ): T
}
