package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.EventEnvelope
import java.time.Duration
import java.util.concurrent.CompletableFuture


interface RawEventPublisherPort {



    /**
     * Publish an envelope asynchronously.
     */
    fun <T : Event> publishAsync(
        eventType: String,
        partitionKey: String, // Explicitly passed from the DB entry
        traceId: String,
        eventId: String,
        parentEventId: String?,
        payload: String
    ): CompletableFuture<*>
}

