package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.ports.outbound.CentralOutboxRelayPort
import com.dogancaglar.common.time.Utc
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.CompletableFuture

/**
 * Unit tests for CentralOutboxDispatchWorker — the claim → publish → mark loop.
 *
 * Pins the worker-side ordering/failure contract (L1 of the ordering model):
 * - per-aggregate groups publish sequentially in oeid order (await before next send)
 * - failure at entry N: unclaim N ONCE with the SAME workerId used for the claim,
 *   break the chain — the TAIL (N+1…) is PARKED: neither published nor unclaimed,
 *   left PROCESSING for the reclaimer (2–4 min latency by design)
 * - other aggregates are unaffected by one aggregate's failure
 * - unclaim errors are swallowed ("relies on reclaimer"); empty batch submits nothing
 *
 * The executor is mocked to run submitted tasks inline, making the async flow deterministic.
 */
class CentralOutboxDispatchWorkerTest {

    private val port = mockk<CentralOutboxRelayPort>(relaxed = true)
    private val kafkaHelper = mockk<CentralOutboxKafkaHelper>()
    private val executor = mockk<ThreadPoolTaskExecutor>()
    private val workerIdSlot = slot<String>()

    private lateinit var worker: CentralOutboxDispatchWorker

    @BeforeEach
    fun setUp() {
        every { executor.execute(any()) } answers { firstArg<Runnable>().run() } // inline = deterministic
        every { port.computeTSafe() } returns Utc.nowInstant()
        worker = CentralOutboxDispatchWorker(port, kafkaHelper, executor, batchSize = 500, appInstanceId = "test-relay")
    }

    private fun event(oeid: Long, aggregateId: String): OutboxEvent =
        OutboxEvent.createNew(
            oeid = oeid,
            partitionKey = "pk-$aggregateId",
            eventType = "payment_authorized",
            aggregateId = aggregateId,
            eventId = "evt-$oeid",
            parentEventId = "evt-$oeid",
            payload = "{}"
        )

    @Test
    fun `success path should publish each entry in oeid order and mark each dispatched`() {
        val e1 = event(10, "agg-X")
        val e2 = event(11, "agg-X")
        every { port.findEligible(any(), any(), capture(workerIdSlot)) } returns listOf(e1, e2)
        every { kafkaHelper.processEntryAsync(any()) } returns CompletableFuture.completedFuture(Unit)

        worker.centralOutboxRelayBatchWorker()

        io.mockk.verifyOrder {
            kafkaHelper.processEntryAsync(e1)
            port.markDispatched(10, any())
            kafkaHelper.processEntryAsync(e2)
            port.markDispatched(11, any())
        }
        verify(exactly = 0) { port.unclaimSpecific(any(), any(), any()) }
    }

    @Test
    fun `failure at entry N should unclaim N once with the claiming workerId and PARK the tail`() {
        val e10 = event(10, "agg-X")
        val e11 = event(11, "agg-X")
        val e12 = event(12, "agg-X")
        every { port.findEligible(any(), any(), capture(workerIdSlot)) } returns listOf(e10, e11, e12)
        every { kafkaHelper.processEntryAsync(e10) } returns CompletableFuture.completedFuture(Unit)
        every { kafkaHelper.processEntryAsync(e11) } returns
            CompletableFuture.failedFuture<Any>(RuntimeException("broker unavailable"))

        worker.centralOutboxRelayBatchWorker()

        // N-1 published + marked; N unclaimed ONCE with the SAME workerId the claim used
        verify(exactly = 1) { port.markDispatched(10, any()) }
        verify(exactly = 1) { port.unclaimSpecific(11, any(), workerIdSlot.captured) }
        // the TAIL is PARKED: e12 neither published nor unclaimed (reclaimer's job later)
        verify(exactly = 0) { kafkaHelper.processEntryAsync(e12) }
        verify(exactly = 0) { port.unclaimSpecific(12, any(), any()) }
        verify(exactly = 0) { port.markDispatched(11, any()) }
        verify(exactly = 0) { port.markDispatched(12, any()) }
    }

    @Test
    fun `one aggregate failing should not affect another aggregate in the same batch`() {
        val failing = event(10, "agg-X")
        val healthy = event(20, "agg-Y")
        every { port.findEligible(any(), any(), any()) } returns listOf(failing, healthy)
        every { kafkaHelper.processEntryAsync(failing) } returns
            CompletableFuture.failedFuture<Any>(RuntimeException("boom"))
        every { kafkaHelper.processEntryAsync(healthy) } returns CompletableFuture.completedFuture(Unit)

        worker.centralOutboxRelayBatchWorker()

        verify(exactly = 1) { port.unclaimSpecific(10, any(), any()) }
        verify(exactly = 1) { port.markDispatched(20, any()) }
        verify(exactly = 0) { port.unclaimSpecific(20, any(), any()) }
    }

    @Test
    fun `unclaim throwing should be swallowed - relies on reclaimer`() {
        val e = event(10, "agg-X")
        every { port.findEligible(any(), any(), any()) } returns listOf(e)
        every { kafkaHelper.processEntryAsync(e) } returns
            CompletableFuture.failedFuture<Any>(RuntimeException("publish failed"))
        every { port.unclaimSpecific(any(), any(), any()) } throws RuntimeException("db down too")

        assertDoesNotThrow { worker.centralOutboxRelayBatchWorker() }
    }

    @Test
    fun `empty batch should submit nothing to the executor`() {
        every { port.findEligible(any(), any(), any()) } returns emptyList()

        worker.centralOutboxRelayBatchWorker()

        verify(exactly = 0) { executor.execute(any()) }
    }
}
