package com.dogancaglar.paymentservice.infra.adapter.outbound.concurrency

import com.dogancaglar.paymentservice.ports.outbound.ResilientExecutionPort
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class ResilientExecutionAdapter(
    @Qualifier("resilientExecutor") private val executor: Executor
) : ResilientExecutionPort {
    private val logger = LoggerFactory.getLogger(ResilientExecutionAdapter::class.java)

   @WithSpan("resilientExecutor")
    override fun <T> executeWithTimeoutAndBackgroundFallback(
        primaryTask: CompletableFuture<T>,
        timeoutMs: Long,
        onTimeoutFallback: () -> T,
        onBackgroundSuccess: (T) -> Unit,
        onBackgroundFailure: (Throwable) -> Unit
    ): T {
        return try {
            primaryTask.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            logger.warn("Task timed out after ${timeoutMs}ms. Returning fallback and continuing in background.")
            
            // Handle background completion
            primaryTask.whenCompleteAsync({ result, error ->
                if (error != null) {
                    logger.error("Background task failed after timeout", error)
                    onBackgroundFailure(error)
                } else {
                    logger.info("Background task completed successfully after timeout")
                    onBackgroundSuccess(result)
                }
            }

                , executor)

            onTimeoutFallback()
        } catch (e: Exception) {
            val cause = e.cause ?: e
            logger.error("Task failed immediately: ${cause.message}")
            throw cause
        }
    }
}
