package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.payment.InternalTransfer
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.domain.model.payment.Payment

interface CentralDbTransactionalFacadePort {

    /**
     * Records the authorization step, which creates the Payment: Payment + AuthTx + journals + outbox events
     * in one transaction. Returns false, writing nothing, if this intent already has a Payment (a replay).
     */
    fun recordAuthorizationInLedger(
        payment: Payment,
        tx: Tx,
        journalEntries: List<JournalEntry>,
        outboxEvents: List<OutboxEvent>
    ): Boolean

    fun recordPaymentOperationInLedger(
        payment: Payment,
        tx: Tx,
        journalEntries: List<JournalEntry> = emptyList(),
        outboxEvents: List<OutboxEvent> = emptyList()
    )

    fun recordInternalTransferOperationInLedger(
        internalTransfer: InternalTransfer,
        journalEntries: List<JournalEntry> = emptyList(),
        outboxEvents: List<OutboxEvent> = emptyList()
    )
}
