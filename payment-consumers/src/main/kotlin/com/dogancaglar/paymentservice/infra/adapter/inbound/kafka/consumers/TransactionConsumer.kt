package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka.consumers

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.kafka.metadata.ConsumerGroups
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
        containerFactory = ConsumerGroups.TRANSACTION_CONSUMER + "-factory",
        groupId = ConsumerGroups.TRANSACTION_CONSUMER
    )
    fun consume(record: ConsumerRecord<String, EventEnvelope<JournalEntriesRecorded>>) {
        val envelope = record.value()
        EventLogContext.with(envelope) {
            val eventId = envelope.data.deterministicEventId()
            if (dedupe.exists(ConsumerGroups.TRANSACTION_CONSUMER, eventId)) {
                logger.warn("⚠️ Event is processed already, skipping eventId=$eventId")
                return@with
            }
            transactionUseCase.updateTransactions(envelope.data)
            dedupe.markProcessed(
                ConsumerGroups.TRANSACTION_CONSUMER,
                eventId,
                EventDeduplicationPort.PROCESSED_EVENT_TTL_SECONDS
            )
        }
    }
}
