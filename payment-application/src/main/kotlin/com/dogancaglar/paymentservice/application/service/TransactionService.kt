package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.application.events.JournalEntryEventData
import com.dogancaglar.paymentservice.application.transaction.Transaction
import com.dogancaglar.paymentservice.application.transaction.TransactionFilter
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.payment.CardBrand
import com.dogancaglar.paymentservice.domain.model.payment.CardSummary
import com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.OrderId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.ports.inbound.usecases.TransactionUseCase
import com.dogancaglar.paymentservice.ports.outbound.TransactionRepository

/**
 * Follows a payment through the ledger's bookings:
 * - AUTHORIZATION: the transaction is saved, with buyer, order, PSP reference and splits
 * - CAPTURE: marked captured (its sellers' split amounts become incoming)
 * - SETTLEMENT: marked settled (its sellers' split amounts become available)
 * Other bookings change nothing the back office displays.
 */
class TransactionService(
    private val transactionRepository: TransactionRepository
) : TransactionUseCase {

    override fun updateTransactions(event: JournalEntriesRecorded) {
        for (entry in event.ledgerEntries) {
            when (entry.journalType) {
                JournalType.AUTHORIZATION -> transactionRepository.save(authorizedTransaction(event, entry))
                JournalType.CAPTURE -> transactionRepository.markCaptured(PaymentId(entry.paymentId), entry.createdAt)
                JournalType.SETTLEMENT -> transactionRepository.markSettled(PaymentId(entry.paymentId), entry.createdAt)
                else -> {}
            }
        }
    }

    override fun getTransaction(paymentId: PaymentId, merchantAccount: String?): Transaction? =
        transactionRepository.findById(paymentId, merchantAccount)

    override fun findTransactions(filter: TransactionFilter, page: Int, size: Int): List<Transaction> {
        require(page >= 0) { "page must be 0 or more" }
        require(size in 1..MAX_PAGE_SIZE) { "size must be between 1 and $MAX_PAGE_SIZE" }
        return transactionRepository.findPage(filter, page * size, size)
    }

    override fun countTransactions(filter: TransactionFilter): Long = transactionRepository.count(filter)

    private fun authorizedTransaction(event: JournalEntriesRecorded, entry: JournalEntryEventData): Transaction {
        // every AUTHORIZATION event carries these; one without them cannot be displayed (goes to the DLQ)
        val details = requireNotNull(event.authorization) {
            "AUTHORIZATION batch ${event.ledgerBatchId} carries no payment details"
        }
        val splits = mutableListOf<PaymentSplit>()
        for (split in details.splits) {
            splits.add(split.toDomain())
        }
        return Transaction(
            paymentId = PaymentId(entry.paymentId),
            paymentIntentId = PaymentIntentId(event.paymentIntentId.toLong()),
            publicPaymentIntentId = event.publicPaymentIntentId,
            merchantAccount = event.merchantAccount,
            buyerId = BuyerId(details.buyerId),
            orderId = OrderId(details.orderId),
            pspReference = details.pspReference,
            processingModel = ProcessingModel.valueOf(details.processingModel),
            totalAmount = Amount.of(event.amountValue, Currency(event.currency)),
            splits = splits,
            authorizedAt = entry.createdAt,
            cardSummary = cardSummaryOf(details.cardBrand, details.cardLast4)
        )
    }

    // brand + last 4 travel together; an event without them (the PSP did not say) has no card summary
    private fun cardSummaryOf(brand: String?, last4: String?): CardSummary? {
        if (brand == null || last4 == null) {
            return null
        }
        return CardSummary.of(CardBrand.valueOf(brand), last4)
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
