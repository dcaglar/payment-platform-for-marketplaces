package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka.consumers

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.kafka.metadata.CONSUMER_GROUPS
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.common.logging.EventLogContext
import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.ports.inbound.usecases.TransactionUseCase
import com.dogancaglar.paymentservice.ports.outbound.EventDeduplicationPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Keeps the back office's transactions up to date from the ledger's events. Its own consumer group on
 * journal.entries.recorded, next to the balance and allocation consumers. Writes only the transactions
 * tables, never the ledger, and publishes nothing.
 */
@Component
class TransactionConsumer(
    private val transactionUseCase: TransactionUseCase,
    private val dedupe: EventDeduplicationPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [Topics.JOURNAL_ENTRIES_RECORDED],
        containerFactory = CONSUMER_GROUPS.TRANSACTION_CONSUMER + "-factory",
        groupId = CONSUMER_GROUPS.TRANSACTION_CONSUMER
    )
    fun consume(record: ConsumerRecord<String, EventEnvelope<JournalEntriesRecorded>>) {
        val envelope = record.value()
        EventLogContext.with(envelope) {
            val eventId = envelope.data.deterministicEventId()
            if (dedupe.exists(CONSUMER_GROUPS.TRANSACTION_CONSUMER, eventId)) {
                logger.warn("⚠️ Event is processed already, skipping eventId=$eventId")
                return@with
            }
            try {
                transactionUseCase.updateTransactions(envelope.data)
                dedupe.markProcessed(CONSUMER_GROUPS.TRANSACTION_CONSUMER, eventId, 3600)
            } catch (e: Exception) {
                logger.error("❌ Failed to update transactions from ledger batch {}", eventId, e)
                throw e // the shared error handler retries or sends it to the DLQ
            }
        }
    }
}
