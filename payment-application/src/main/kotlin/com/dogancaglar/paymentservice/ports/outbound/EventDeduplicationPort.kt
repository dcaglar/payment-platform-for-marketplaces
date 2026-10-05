package com.dogancaglar.paymentservice.ports.outbound

/**
 * "Has THIS consumer group already processed THIS event?"
 *
 * The consumer group is part of the record on purpose: several consumer groups can read the same topic
 * (independent fan-out), and one group marking an event must never make another group skip it.
 */
interface EventDeduplicationPort {
    fun exists(prefix: String, eventId: String): Boolean
    fun markProcessed(prefix: String, eventId: String, ttlSeconds: Long)

    companion object {
        /** How long a processed event is remembered: a redelivery within an hour is skipped. */
        const val PROCESSED_EVENT_TTL_SECONDS = 3600L
    }
}
