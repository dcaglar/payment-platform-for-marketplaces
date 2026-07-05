package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.EventEnvelope
import java.time.Duration
import java.util.concurrent.CompletableFuture


interface EventPublisherPort {



    /**
     * Publish an envelope asynchronously.
     */
    fun <T : Event> publishAsync(
        envelope: EventEnvelope<T>
    ): CompletableFuture<EventEnvelope<T>>
}

