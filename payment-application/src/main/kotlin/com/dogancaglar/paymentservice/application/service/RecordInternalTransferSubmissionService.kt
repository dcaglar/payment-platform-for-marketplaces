package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.events.InternalTransferCommand
import com.dogancaglar.paymentservice.application.util.toPublicPaymentIntentId
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.payment.InternalTransfer
import com.dogancaglar.paymentservice.domain.model.payment.Payment
import com.dogancaglar.paymentservice.domain.model.vo.InternalTransferId
import com.dogancaglar.paymentservice.ports.inbound.usecases.RecordInternalTransferSubmissionUseCase
import com.dogancaglar.paymentservice.ports.outbound.CentralDbTransactionalFacadePort
import com.dogancaglar.paymentservice.ports.outbound.IdGeneratorPort
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import org.slf4j.LoggerFactory

class RecordInternalTransferSubmissionService(
    private val centralDbTransactionalFacadePort: CentralDbTransactionalFacadePort,
    private val idGeneratorPort: IdGeneratorPort,
    private val outboxEventFactoryPort: OutboxEventFactoryPort,
) : RecordInternalTransferSubmissionUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun recordSubmission(
        payment: Payment,
        sourceAccount: String,
        targetAccount: String,
        transferAmount: Amount,
        journalType: JournalType,
        reason: String
    ) {
        val paymentIntentId = payment.paymentIntentId
        val transferId = InternalTransferId(idGeneratorPort.generateId()) // using same generator for simplicity

        // 1. Create InternalTransfer and mark as SENT_FOR_TRANSFER
        val internalTransfer = InternalTransfer.createNew(
            transferId = transferId,
            paymentIntentId = paymentIntentId,
            paymentId = payment.paymentId,
            merchantAccount = payment.merchantAccount,
            amount = transferAmount,
            sourceAccount = sourceAccount,
            targetAccount = targetAccount,
            transferType = journalType.name
        ).markSentForTransfer()

        // 3. Create EventEnvelope for Outbox
        val command = InternalTransferCommand.from(
            transfer = internalTransfer,
            paymentIntentId = paymentIntentId.value.toString(),
            publicPaymentIntentId = paymentIntentId.toPublicPaymentIntentId(),
            journalType = journalType.name,
        )

        val outboxEvent = outboxEventFactoryPort.create(command)
        // 4. Atomically persist
        logger.debug(
            "Persisting InternalTransfer and Tx for paymentIntentId=$paymentIntentId, transferId=${transferId.value}"
        )
        //  notice transfertx s not vreated, because internal transfer do not refer to external trransaction
        centralDbTransactionalFacadePort.recordInternalTransferOperationInLedger(
            internalTransfer = internalTransfer,
            outboxEvents = listOf(outboxEvent),
            journalEntries = emptyList()
        )
    }
}
