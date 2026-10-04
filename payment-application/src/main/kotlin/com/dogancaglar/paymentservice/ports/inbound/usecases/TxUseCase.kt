package com.dogancaglar.paymentservice.ports.inbound.usecases

import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId

/**
 * Finance reads a payment's txs and journal entries. Everything is looked up together with the merchant: another
 * merchant's payment or tx is not found (empty / null).
 */
interface TxUseCase {

    /** The payment's txs, oldest first; empty if the merchant has no such payment. */
    fun findTxsOfPayment(paymentId: PaymentId, merchantAccount: String): List<Tx>

    /** All of the payment's journal entries, in recorded order; empty if the merchant has no such payment. */
    fun findJournalEntriesOfPayment(paymentId: PaymentId, merchantAccount: String): List<JournalEntry>

    /** The tx, or null if the merchant has no such tx. */
    fun getTx(txId: TxId, merchantAccount: String): Tx?

    /** The tx's journal entries; empty if the merchant has no such tx. */
    fun findJournalEntriesOfTx(txId: TxId, merchantAccount: String): List<JournalEntry>
}
