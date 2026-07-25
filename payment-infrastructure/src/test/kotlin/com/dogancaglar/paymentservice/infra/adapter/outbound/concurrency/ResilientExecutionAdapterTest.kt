package com.dogancaglar.paymentservice.infra.adapter.outbound.concurrency

import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ResilientExecutionAdapterTest {

    // Use a real single-threaded executor for testing background tasks
    private val executor = Executors.newSingleThreadExecutor()
    private val adapter = ResilientExecutionAdapter(executor)

    @Test
    fun `execute returns result immediately when task completes within timeout`() {
        // given
        val expectedResult = "success"
        val future = CompletableFuture.completedFuture(expectedResult)
        
        var backgroundSuccessCalled = false
        var backgroundFailureCalled = false

        // when
        val result = adapter.executeWithTimeoutAndBackgroundFallback(
            primaryTask = { future },
            timeoutMs = 1000,
            onTimeoutFallback = { "fallback" },
            onBackgroundSuccess = { backgroundSuccessCalled = true },
            onBackgroundFailure = { backgroundFailureCalled = true }
        )

        // then
        assertEquals(expectedResult, result)
        // Background callbacks should NOT be called for immediate success
        assertEquals(false, backgroundSuccessCalled)
        assertEquals(false, backgroundFailureCalled)
    }

    @Test
    fun `execute returns fallback when task times out, then runs background success`() {
        // given
        val future = CompletableFuture<String>()
        val fallbackValue = "fallback"
        val successLatch = CountDownLatch(1)
        
        var capturedResult: String? = null

        // when
        val result = adapter.executeWithTimeoutAndBackgroundFallback(
            primaryTask = { future },
            timeoutMs = 100, // Short timeout
            onTimeoutFallback = { fallbackValue },
            onBackgroundSuccess = { res -> 
                capturedResult = res
                successLatch.countDown()
            },
            onBackgroundFailure = { }
        )

        // then - verify we got fallback immediately
        assertEquals(fallbackValue, result)

        // Complete the future LATER
        future.complete("delayed-success")

        // Wait for background thread to process
        val callbackExecuted = successLatch.await(2, TimeUnit.SECONDS)
        
        assertEquals(true, callbackExecuted, "Background success callback should have executed")
        assertEquals("delayed-success", capturedResult)
    }

    @Test
    fun `execute returns fallback when task times out, then runs background failure`() {
        // given
        val future = CompletableFuture<String>()
        val fallbackValue = "fallback"
        val failureLatch = CountDownLatch(1)
        val expectedError = RuntimeException("Boom")
        
        var capturedError: Throwable? = null

        // when
        val result = adapter.executeWithTimeoutAndBackgroundFallback(
            primaryTask = { future },
            timeoutMs = 100, // Short timeout
            onTimeoutFallback = { fallbackValue },
            onBackgroundSuccess = { },
            onBackgroundFailure = { err: Throwable -> 
                capturedError = err
                failureLatch.countDown()
            }
        )

        // then - verify we got fallback immediately
        assertEquals(fallbackValue, result)

        // Fail the future LATER
        future.completeExceptionally(expectedError)

        // Wait for background thread to process
        val callbackExecuted = failureLatch.await(2, TimeUnit.SECONDS)
        
        assertEquals(true, callbackExecuted, "Background failure callback should have executed")
        assertEquals(expectedError, capturedError)
    }

    @Test
    fun `execute throws exception immediately when task fails within timeout`() {
        // given
        val expectedError = RuntimeException("Immediate Boom")
        val future = CompletableFuture<String>()
        future.completeExceptionally(expectedError)

        // when & then
        val exception = assertThrows(RuntimeException::class.java) {
            adapter.executeWithTimeoutAndBackgroundFallback<String>(
                primaryTask = { future },
                timeoutMs = 1000,
                onTimeoutFallback = { "fallback" },
                onBackgroundSuccess = { },
                onBackgroundFailure = { }
            )
        }

        assertEquals("Immediate Boom", exception.message)
    }
}
