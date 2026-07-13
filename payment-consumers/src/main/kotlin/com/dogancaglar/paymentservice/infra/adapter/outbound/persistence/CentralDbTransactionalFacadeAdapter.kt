package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.LedgerMapper
import com.dogancaglar.common.db.converter.LedgerEntitiyMapper
import com.dogancaglar.common.db.converter.PaymentEntityMapper
import com.dogancaglar.common.db.converter.PaymentTxEntityMapper

import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.payment.Payment
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.PaymentMapper
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.PaymentTxMapper
import com.dogancaglar.paymentservice.ports.outbound.CentralDbTransactionalFacadePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Qualifier
import com.dogancaglar.paymentservice.application.dto.PaymentSplitDto
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.CentralOutboxWriterMapper
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.TransferMapper

@Repository
open class CentralDbTransactionalFacadeAdapter(
    private val ledgerMapper: LedgerMapper,
    private val paymentMapper: PaymentMapper,
    private val txMapper: PaymentTxMapper,
    private val centralOutboxWriterMapper: CentralOutboxWriterMapper,
    private val transferMapper: TransferMapper,
    @Qualifier("myObjectMapper") private val objectMapper: ObjectMapper
) : CentralDbTransactionalFacadePort {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(timeout = 5)
    override fun recordPaymentOperationInLedger(
        payment: Payment,
        tx: Tx,
        journalEntries: List<JournalEntry>,
        outboxEvents: List<OutboxEvent>
    ) {
        val splitsJson = objectMapper.writeValueAsString(payment.splits.map { PaymentSplitDto.fromDomain(it) })
        val paymentEntity = PaymentEntityMapper.toEntity(payment, splitsJson)
        paymentMapper.upsert(paymentEntity)
        val txEntity = PaymentTxEntityMapper.toEntity(tx)
        txMapper.upsert(txEntity)
        saveJournalAndOutbox(journalEntries, outboxEvents)
    }

    @Transactional(timeout = 5)
    override fun recordInternalTransferOperationInLedger(
        internalTransfer: com.dogancaglar.paymentservice.domain.model.payment.InternalTransfer,
        journalEntries: List<JournalEntry>,
        outboxEvents: List<OutboxEvent>
    ) {
        val transferEntity = com.dogancaglar.common.db.converter.TransferEntityMapper.toEntity(internalTransfer)
        transferMapper.upsert(transferEntity)
        saveJournalAndOutbox(journalEntries, outboxEvents)
    }

    private fun saveJournalAndOutbox(
        journalEntries: List<JournalEntry>,
        outboxEvents: List<OutboxEvent>
    ) {
        if (journalEntries.isNotEmpty()) {
            journalEntries.forEach { entry ->
                // 1. Insert journal entry (idempotent via ON CONFLICT)
                val journalEntity = LedgerEntitiyMapper.toJournalEntryEntity(entry)
                val journalInserted = ledgerMapper.insertJournalEntry(journalEntity)
                if (journalInserted == 0) {
                    logger.debug("🟦 Duplicate journal entry id={} — skipping insert", journalEntity.id)
                    return@forEach
                }

                // 2. Insert postings
                LedgerEntitiyMapper.toPostingEntities(entry).forEach { posting ->
                    ledgerMapper.insertPosting(posting)
                }

                logger.debug("💾 Journal entry persisted with id={}", journalEntity.id)
            }
            logger.info("💾 Journal batch persisted with {} entries", journalEntries.size)
        }

        if (outboxEvents.isNotEmpty()) {
            val outboxEntities = outboxEvents.map { com.dogancaglar.common.db.converter.OutboxEventEntityMapper.toEntity(it) }
            centralOutboxWriterMapper.insertAllOutboxEvents(outboxEntities)
            logger.info("💾 Outbox batch persisted with {} events", outboxEvents.size)
        }
    }
}