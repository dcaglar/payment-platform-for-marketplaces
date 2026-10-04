package com.dogancaglar.paymentservice.ports.inbound.usecases

import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.application.transaction.Transaction
import com.dogancaglar.paymentservice.application.transaction.TransactionFilter
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId

/** The back office's transactions: kept up to date from the ledger's events, and read by the screens. */
interface TransactionUseCase {
    fun updateTransactions(event: JournalEntriesRecorded)

    /** [merchantAccount]: only that merchant's transaction; null = any merchant (staff). */
    fun getTransaction(paymentId: PaymentId, merchantAccount: String?): Transaction?

    /** [page] starts at 0. */
    fun findTransactions(filter: TransactionFilter, page: Int, size: Int): List<Transaction>

    fun countTransactions(filter: TransactionFilter): Long
}
