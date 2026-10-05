package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.application.transaction.Transaction
import com.dogancaglar.paymentservice.application.transaction.TransactionFilter
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import java.time.Instant

/** Stores the back office's transactions. Every call is idempotent: repeating it changes nothing. */
interface TransactionRepository {

    /** Saves the transaction with its splits, unless it is already saved. */
    fun save(transaction: Transaction)

    /**
     * Sets the capture time unless already set. Fails if the transaction is not saved yet (it is saved at
     * authorization).
     */
    fun markCaptured(paymentId: PaymentId, capturedAt: Instant)

    /** Sets the settlement time unless already set. Fails if the transaction is not saved yet. */
    fun markSettled(paymentId: PaymentId, settledAt: Instant)

    /**
     * The transaction with its splits, or null if there is none. With [merchantAccount] only that merchant's
     * (another merchant's is not found); null = any merchant (staff).
     */
    fun findById(paymentId: PaymentId, merchantAccount: String?): Transaction?

    /** One page of transactions matching the filter, newest first (without splits). */
    fun findPage(filter: TransactionFilter, offset: Int, limit: Int): List<Transaction>

    /** How many transactions match the filter. */
    fun count(filter: TransactionFilter): Long
}
