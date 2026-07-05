package com.dogancaglar.common.event.metadata

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.EventEnvelope
import com.fasterxml.jackson.core.type.TypeReference

/**
 * Kafka-level metadata for a specific event type:
 *  - topic
 *  - stable eventType
 *  - partition key function
 *  - Jackson type information
 */
interface EventMetadata<T : Event> {
    val topic: String
    val eventType: String

    val clazz: Class<T>
    val typeRef: TypeReference<EventEnvelope<T>>

    /**
     * Partitioning strategy: which key to use for Kafka partition key.
     * Usually returns event.aggregateId or something derived from it.
     */
}