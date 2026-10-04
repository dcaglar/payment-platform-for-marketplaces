package com.dogancaglar.common.db.converter

import com.dogancaglar.common.db.entity.TransactionEntity
import com.dogancaglar.common.db.entity.TransactionSplitEntity
import com.dogancaglar.paymentservice.application.transaction.Transaction
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.OrderId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId

/** Transaction to rows and back: the transaction itself and one row per split, numbered in the order they were sent. */
object TransactionEntityMapper {

    fun toEntity(transaction: Transaction) = TransactionEntity(
        paymentId = transaction.paymentId.value,
        paymentIntentId = transaction.paymentIntentId.value,
        publicPaymentIntentId = transaction.publicPaymentIntentId,
        merchantAccount = transaction.merchantAccount,
        buyerId = transaction.buyerId.value,
        orderId = transaction.orderId.value,
        pspReference = transaction.pspReference,
        processingModel = transaction.processingModel.name,
        totalAmount = transaction.totalAmount.quantity,
        currency = transaction.totalAmount.currency.currencyCode,
        authorizedAt = transaction.authorizedAt,
        capturedAt = transaction.capturedAt,
        settledAt = transaction.settledAt,
        cardBrand = transaction.cardSummary?.brand?.name,
        cardLast4 = transaction.cardSummary?.last4
    )

    fun toSplitEntities(transaction: Transaction): List<TransactionSplitEntity> {
        val rows = mutableListOf<TransactionSplitEntity>()
        var lineNo = 1
        for (split in transaction.splits) {
            rows.add(
                TransactionSplitEntity(
                    paymentId = transaction.paymentId.value,
                    lineNo = lineNo,
                    accountType = split.accountType.name,
                    account = split.account,
                    amount = split.amount.quantity,
                    currency = split.amount.currency.currencyCode
                )
            )
            lineNo++
        }
        return rows
    }

    fun toTransaction(row: TransactionEntity, splitRows: List<TransactionSplitEntity>): Transaction {
        val currency = Currency(row.currency)
        val splits = mutableListOf<PaymentSplit>()
        for (split in splitRows) {
            splits.add(
                PaymentSplit.of(
                    LedgerAccountType.valueOf(split.accountType),
                    split.account,
                    Amount.of(split.amount, Currency(split.currency))
                )
            )
        }
        return Transaction(
            paymentId = PaymentId(row.paymentId),
            paymentIntentId = PaymentIntentId(row.paymentIntentId),
            publicPaymentIntentId = row.publicPaymentIntentId,
            merchantAccount = row.merchantAccount,
            buyerId = BuyerId(row.buyerId),
            orderId = OrderId(row.orderId),
            pspReference = row.pspReference,
            processingModel = ProcessingModel.valueOf(row.processingModel),
            totalAmount = Amount.of(row.totalAmount, currency),
            splits = splits,
            authorizedAt = row.authorizedAt,
            capturedAt = row.capturedAt,
            settledAt = row.settledAt,
            cardSummary = PaymentIntentEntityMapper.cardSummaryOf(row.cardBrand, row.cardLast4)
        )
    }
}
