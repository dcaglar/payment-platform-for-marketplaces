package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka.consumers

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.application.util.LedgerDomainEventEntityMapper
import com.dogancaglar.common.kafka.metadata.CONSUMER_GROUPS
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.paymentservice.ports.inbound.usecases.AccountBalanceUseCase
import com.dogancaglar.paymentservice.ports.outbound.EventDeduplicationPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.apache.kafka.clients.consumer.Consumer

/**
 * Batch consumer for updating account balances from ledger entries.
 * 
 * Implements:
 * - Batch processing (100-500 events)
 * - Idempotency checks (Redis SET for ledgerEntryIds)
 * - Redis delta updates (TTL-based cleanup)
 */
@Component
class AccountBalanceConsumer(
    private val accountBalanceService: AccountBalanceUseCase,
    private val dedupe: EventDeduplicationPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    

    @KafkaListener(
        topics = [Topics.JOURNAL_ENTRIES_RECORDED],
        containerFactory = CONSUMER_GROUPS.ACCOUNT_BALANCE_CONSUMER + "-factory",
        groupId = CONSUMER_GROUPS.ACCOUNT_BALANCE_CONSUMER
    )
    fun onLedgerEntriesRecorded(
        records: List<ConsumerRecord<String, EventEnvelope<JournalEntriesRecorded>>>,
        consumer: Consumer<*, *>
    ) {
        // Deduplication: Filter out already-processed events and log duplicates
        val newRecords = records.filter { record ->
            val envelope = record.value() as EventEnvelope<JournalEntriesRecorded>
            val singleDedupeKey = envelope.data.deterministicEventId()
            val exists = dedupe.exists(singleDedupeKey)
            if (exists) {
                logger.warn(
                    "⚠️ Event is processed already, skipping deterministing eventid $singleDedupeKey eventId=${record.value().eventId}, aggregateId=${record.value().aggregateId}"
                )
            }
            !exists
        }
        
        // Extract all ledger entry domain from batch
        val allLedgerEntriesDomain = newRecords
            .flatMap { it.value().data.ledgerEntries }
            .map {
                logger.debug(
                    "🎬 Processing  journal ${it.journalType.name} with journal entry id ${it.journalEntryId}  global journal entry id ${it.globalJournalEntryId} ")
                LedgerDomainEventEntityMapper.toDomain(it) }
        // idempotenct update Process batch with idempotency check
        accountBalanceService.updateAccountBalancesBatch(allLedgerEntriesDomain)
        
        // Mark processed events
        newRecords.forEach {
            val currentEventEnvelope = it.value()
            val currentDedupeKey =   currentEventEnvelope.data.deterministicEventId()
            dedupe.markProcessed(currentDedupeKey , 3600)
        }

        logger.info("Account balance consumer executed successfully for batch size=${records.size}")
    }
}

