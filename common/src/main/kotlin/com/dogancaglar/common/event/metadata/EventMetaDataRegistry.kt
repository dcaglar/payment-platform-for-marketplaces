package com.dogancaglar.common.event.metadata

import com.dogancaglar.common.event.Event

/**
 * Runtime registry that maps eventType → EventMetadata<T>.
 *
 * This is intentionally pure and generic:
 *  - common module knows NOTHING about payment, Kafka, infra.
 *  - payment-infra registers its own metadata list at runtime.
 *  - publishers and consumers resolve metadata dynamically.
 */
class EventMetaDataRegistry(
    metadatas: List<EventMetadata<*>>
) {

    private val byType: Map<String, EventMetadata<*>> =
        metadatas.associateBy { it.eventType }

    /**
     * Resolve EventMetadata by eventType string.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Event> metadataFor(eventType: String): EventMetadata<T> {
        return byType[eventType] as? EventMetadata<T>
            ?: error("❌ No EventMetadata registered for eventType=$eventType")
    }

    /**
     * Resolve metadata directly from the event instance.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Event> metadataForEvent(event: T): EventMetadata<T> {
        return metadataFor(event.eventType)
    }

    /**
     * Optional: resolve metadata from topic (if someone wants it).
     * You can remove this if you don't want to support topic lookups.
     */
    fun all(): Collection<EventMetadata<*>> = byType.values
}
