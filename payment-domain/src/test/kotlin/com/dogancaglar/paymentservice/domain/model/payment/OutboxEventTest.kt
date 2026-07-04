package com.dogancaglar.paymentservice.domain.model.payment

import com.dogancaglar.common.time.Utc
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OutboxEventTest {

    private val now = Utc.nowLocalDateTime()
    private val testOeid = 123L
    private val testPartitionKey = "pk-100"
    private val testEventType = "PaymentOrderCreated"
    private val testAggregateId = "paymentorder-123"
    private val testTraceId = "trace-abc-123"
    private val testEventId = "event-xyz-789"
    private val testParentEventId = "parent-id-000"
    private val testPayload = """{"paymentOrderId":"123","amount":10000}"""
    private val testCreatedAt = now

    // Helper to keep test setup DRY
    private fun createDefaultEvent() = OutboxEvent.createNew(
        oeid = testOeid,
        partitionKey = testPartitionKey,
        eventType = testEventType,
        aggregateId = testAggregateId,
        traceId = testTraceId,
        eventId = testEventId,
        parentEventId = testParentEventId,
        payload = testPayload
    )

    @Test
    fun `createNew should create OutboxEvent with NEW status`() {
        val outboxEvent = createDefaultEvent()

        assertEquals(testOeid, outboxEvent.oeid)
        assertEquals(testPartitionKey, outboxEvent.partitionKey)
        assertEquals(testEventType, outboxEvent.eventType)
        assertEquals(testAggregateId, outboxEvent.aggregateId)
        assertEquals(testTraceId, outboxEvent.traceId)
        assertEquals(testEventId, outboxEvent.eventId)
        assertEquals(testParentEventId, outboxEvent.parentEventId)
        assertEquals(testPayload, outboxEvent.payload)
        assertEquals(OutboxEvent.Status.NEW, outboxEvent.status)
        assertNotNull(outboxEvent.createdAt)
    }

    @Test
    fun `markAsProcessing should change status from NEW to PROCESSING`() {
        val outboxEvent = createDefaultEvent()
        assertEquals(OutboxEvent.Status.NEW, outboxEvent.status)

        val updatedOutboxEvent = outboxEvent.markAsProcessing()

        assertEquals(OutboxEvent.Status.PROCESSING, updatedOutboxEvent.status)
        assertEquals(testOeid, outboxEvent.oeid) // verify original instance unchanged
    }

    @Test
    fun `markAsSent should change status from PROCESSING to SENT`() {
        val outboxEvent = createDefaultEvent().markAsProcessing()
        val updatedOutBoxEvent = outboxEvent.markAsSent()

        assertEquals(OutboxEvent.Status.PROCESSING, outboxEvent.status)
        assertEquals(OutboxEvent.Status.SENT, updatedOutBoxEvent.status)
    }

    @Test
    fun `markAsProcessing should throw exception when status is not NEW`() {
        val outboxEvent = createDefaultEvent().markAsSent()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            outboxEvent.markAsProcessing()
        }

        assertTrue(exception.message!!.contains("Invalid transition from SENT to PROCESSING"))
    }

    @Test
    fun `markAsSent should throw exception when status is SENT`() {
        val outboxEvent = createDefaultEvent().markAsSent()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            outboxEvent.markAsSent()
        }

        assertTrue(exception.message!!.contains("Invalid transition from SENT"))
    }

    @Test
    fun `restore should recreate OutboxEvent with provided status`() {
        val outboxEvent = OutboxEvent.rehydrate(
            oeid = testOeid,
            partitionKey = testPartitionKey,
            eventType = testEventType,
            aggregateId = testAggregateId,
            traceId = testTraceId,
            eventId = testEventId,
            parentEventId = testParentEventId,
            payload = testPayload,
            status = "PROCESSING",
            createdAt = testCreatedAt,
            updatedAt = testCreatedAt
        )

        assertEquals(testOeid, outboxEvent.oeid)
        assertEquals(testPartitionKey, outboxEvent.partitionKey)
        assertEquals(OutboxEvent.Status.PROCESSING, outboxEvent.status)
        assertEquals(testTraceId, outboxEvent.traceId)
    }

    @Test
    fun `should support complete state transition NEW to PROCESSING to SENT`() {
        val outboxEvent = createDefaultEvent()
        assertEquals(OutboxEvent.Status.NEW, outboxEvent.status)

        val processing = outboxEvent.markAsProcessing()
        assertEquals(OutboxEvent.Status.PROCESSING, processing.status)

        val sent = processing.markAsSent()
        assertEquals(OutboxEvent.Status.SENT, sent.status)
    }

    @Test
    fun `should handle different event types`() {
        val eventTypes = listOf("PaymentAuthorized", "PaymentOrderCreated")
        eventTypes.forEach { type ->
            val outboxEvent = OutboxEvent.createNew(
                oeid = testOeid,
                partitionKey = testPartitionKey,
                eventType = type,
                aggregateId = testAggregateId,
                traceId = testTraceId,
                eventId = testEventId,
                parentEventId = testParentEventId,
                payload = testPayload
            )
            assertEquals(type, outboxEvent.eventType)
        }
    }

    @Test
    fun `should preserve immutability of non-status fields`() {
        val outboxEvent = createDefaultEvent()
        val originalOeid = outboxEvent.oeid
        val originalTraceId = outboxEvent.traceId

        outboxEvent.markAsProcessing()

        assertEquals(originalOeid, outboxEvent.oeid)
        assertEquals(originalTraceId, outboxEvent.traceId)
    }
}