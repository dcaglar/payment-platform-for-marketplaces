package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.common.db.converter.TransactionEntityMapper
import com.dogancaglar.paymentservice.application.transaction.Transaction
import com.dogancaglar.paymentservice.application.transaction.TransactionFilter
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.TransactionMapper
import com.dogancaglar.paymentservice.ports.outbound.TransactionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class TransactionRepositoryAdapter(
    private val transactionMapper: TransactionMapper
) : TransactionRepository {

    @Transactional(timeout = 5)
    override fun save(transaction: Transaction) {
        transactionMapper.insertIfAbsent(TransactionEntityMapper.toEntity(transaction))
        for (split in TransactionEntityMapper.toSplitEntities(transaction)) {
            transactionMapper.insertSplitIfAbsent(split)
        }
    }

    override fun markCaptured(paymentId: PaymentId, capturedAt: Instant) {
        val updated = transactionMapper.markCaptured(paymentId.value, capturedAt)
        failIfMissing(updated, paymentId)
    }

    override fun markSettled(paymentId: PaymentId, settledAt: Instant) {
        val updated = transactionMapper.markSettled(paymentId.value, settledAt)
        failIfMissing(updated, paymentId)
    }

    override fun findById(paymentId: PaymentId, merchantAccount: String?): Transaction? {
        val row = transactionMapper.findById(paymentId.value, merchantAccount) ?: return null
        return TransactionEntityMapper.toTransaction(row, transactionMapper.findSplits(paymentId.value))
    }

    override fun findPage(filter: TransactionFilter, offset: Int, limit: Int): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val rows = transactionMapper.findPage(
            filter.merchantAccount, filter.orderId, filter.paymentId?.value, filter.sellerId, filter.status?.name,
            filter.authorizedFrom, filter.authorizedTo, filter.processingModel?.name, offset, limit
        )
        for (row in rows) {
            transactions.add(TransactionEntityMapper.toTransaction(row, emptyList()))
        }
        return transactions
    }

    override fun count(filter: TransactionFilter): Long = transactionMapper.count(
        filter.merchantAccount,
        filter.orderId,
        filter.paymentId?.value,
        filter.sellerId,
        filter.status?.name,
        filter.authorizedFrom,
        filter.authorizedTo,
        filter.processingModel?.name
    )

    // 0 rows: already marked (a replay, fine) or not saved yet. The authorization always comes first, so a missing
    // transaction means its save failed: fail here so the event is retried, then goes to the DLQ, never silently lost.
    private fun failIfMissing(updated: Int, paymentId: PaymentId) {
        if (updated == 0 && !transactionMapper.exists(paymentId.value)) {
            error("No transaction saved for payment ${paymentId.value} yet")
        }
    }
}
