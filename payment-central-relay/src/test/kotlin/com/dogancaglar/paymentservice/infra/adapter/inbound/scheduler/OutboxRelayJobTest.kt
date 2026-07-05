package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.common.kafka.publisher.KafkaDeliveryResult
import com.dogancaglar.common.kafka.publisher.RawEventPublisher
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.ports.outbound.CentralOutboxRelayPort
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Instant
import java.util.concurrent.CompletableFuture

class OutboxRelayJobTest {

    private lateinit var centralOutboxRepository: CentralOutboxRelayPort
    private lateinit var rawEventPublisher: RawEventPublisher
    private lateinit var executor: ThreadPoolTaskExecutor
    private lateinit var meterRegistry: MeterRegistry
    private lateinit var outboxRelayJob: OutboxRelayJob

    @BeforeEach
    fun setUp() {
        centralOutboxRepository = mockk<CentralOutboxRelayPort>(relaxed = true)
        rawEventPublisher = mockk<RawEventPublisher>(relaxed = true)
        executor = mockk<ThreadPoolTaskExecutor>(relaxed = true)

        // Make mock executor run tasks synchronously in tests
        every { executor.execute(any()) } answers {
            val runnable = firstArg<Runnable>()
            try {
                runnable.run()
            } catch (e: Throwable) {
                // Mimics ThreadPoolTaskExecutor background thread swallow/log behavior
            }
        }

        meterRegistry = SimpleMeterRegistry()

        outboxRelayJob = OutboxRelayJob(
            centralOutboxRepository = centralOutboxRepository,
            rawEventPublisher = rawEventPublisher,
            executor = executor,
            batchSize = 100,
            appInstanceId = "test-relay",
            meterRegistry = meterRegistry
        )
    }

    // Helper to create events matching the new rehydrate signature
    private fun createTestEvent(oeid: Long, aggregateId: String, eventType: String): OutboxEvent {
        val now = Utc.nowLocalDateTime()
        return OutboxEvent.rehydrate(
            oeid = oeid,
            partitionKey = "pk-$oeid",
            eventType = eventType,
            aggregateId = aggregateId,
            traceId = "trace-$oeid",
            eventId = "evt-$oeid",
            parentEventId = "parent-$oeid",
            payload = "{\"data\":\"test\"}",
            status = "NEW",
            createdAt = now,
            updatedAt = now
        )
    }

    @Test
    fun `should default tSafe to now if null and continue polling`() {
        every { centralOutboxRepository.computeTSafe() } returns null
        every { centralOutboxRepository.findEligible(any(), any(), any()) } returns emptyList()

        outboxRelayJob.poll()

        verify(exactly = 1) { centralOutboxRepository.findEligible(any(), any(), any()) }
        verify(exactly = 0) { executor.execute(any()) }
    }

    @Test
    fun `should skip poll if no eligible events`() {
        val tSafe = Instant.now()
        every { centralOutboxRepository.computeTSafe() } returns tSafe
        every { centralOutboxRepository.findEligible(tSafe, 100, any()) } returns emptyList()

        outboxRelayJob.poll()

        verify(exactly = 1) { centralOutboxRepository.findEligible(tSafe, 100, any()) }
        verify(exactly = 0) { executor.execute(any()) }
    }

    @Test
    fun `should group events by aggregateId and publish successfully`() {
        val tSafe = Instant.now()
        val event1 = createTestEvent(1L, "seller-1", "PAYMENT_AUTHORIZED")
        val event2 = createTestEvent(2L, "seller-1", "CAPTURE_REQUESTED")
        val event3 = createTestEvent(3L, "seller-2", "CAPTURE_CONFIRMED")

        every { centralOutboxRepository.computeTSafe() } returns tSafe
        every { centralOutboxRepository.findEligible(tSafe, 100, any()) } returns listOf(event1, event2, event3)

        every { rawEventPublisher.publishRaw(any()) } returns CompletableFuture.completedFuture(mockk())

        outboxRelayJob.poll()

        // 2 groups (seller-1, seller-2) = 2 executor tasks
        verify(exactly = 2) { executor.execute(any()) }

        // Verify all 3 events sent
        verify(exactly = 1) { rawEventPublisher.publishRaw(event1) }
        verify(exactly = 1) { rawEventPublisher.publishRaw(event2) }
        verify(exactly = 1) { rawEventPublisher.publishRaw(event3) }

        // Verify marked as dispatched
        verify(exactly = 1) { centralOutboxRepository.markDispatched(1L, any()) }
        verify(exactly = 1) { centralOutboxRepository.markDispatched(2L, any()) }
        verify(exactly = 1) { centralOutboxRepository.markDispatched(3L, any()) }
    }

    @Test
    fun `should not mark event as dispatched if Kafka publish fails`() {
        val tSafe = Instant.now()
        val event = createTestEvent(4L, "seller-1", "PAYMENT_AUTHORIZED")

        every { centralOutboxRepository.computeTSafe() } returns tSafe
        every { centralOutboxRepository.findEligible(tSafe, 100, any()) } returns listOf(event)

        val failedFuture = CompletableFuture<Any>()
        failedFuture.completeExceptionally(RuntimeException("Kafka error"))
        every { rawEventPublisher.publishRaw(event) } returns failedFuture as CompletableFuture<KafkaDeliveryResult>

        outboxRelayJob.poll()

        verify(exactly = 1) { rawEventPublisher.publishRaw(event) }
        verify(exactly = 0) { centralOutboxRepository.markDispatched(4L, any()) }
    }
}