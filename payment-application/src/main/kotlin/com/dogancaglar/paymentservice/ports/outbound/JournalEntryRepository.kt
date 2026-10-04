package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId

/** Reads recorded journal entries with their postings, oldest first. Writing stays with the central-db facade. */
interface JournalEntryRepository {

    /** The journal entries recorded for this tx. */
    fun findByTxId(txId: TxId): List<JournalEntry>

    /** All of the payment's journal entries (with or without a tx), in the order they were recorded. */
    fun findByPaymentId(paymentId: PaymentId): List<JournalEntry>
}
