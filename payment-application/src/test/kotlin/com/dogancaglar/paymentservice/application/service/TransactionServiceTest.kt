package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.dto.PaymentSplitDto
import com.dogancaglar.paymentservice.application.events.AuthorizationDetails
import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.application.events.JournalEntryEventData
import com.dogancaglar.paymentservice.application.events.PostingDirection
import com.dogancaglar.paymentservice.application.events.PostingEventData
import com.dogancaglar.paymentservice.application.transaction.Transaction
import com.dogancaglar.paymentservice.application.transaction.TransactionFilter
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.domain.model.payment.CardBrand
import com.dogancaglar.paymentservice.domain.model.payment.CardSummary
import com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.OrderId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.ports.outbound.TransactionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/** Each ledger booking does exactly its part to the transaction. */
class TransactionServiceTest {

    // records what the service asks the repository to do
    private class RecordingRepository : TransactionRepository {
        val calls = mutableListOf<String>()
        val saved = mutableListOf<Transaction>()
        override fun save(transaction: Transaction) {
            saved.add(transaction)
            calls.add("save ${transaction.paymentId.value}")
        }
        override fun markCaptured(paymentId: PaymentId, capturedAt: Instant) {
            calls.add("markCaptured ${paymentId.value} $capturedAt")
        }
        override fun markSettled(paymentId: PaymentId, settledAt: Instant) {
            calls.add("markSettled ${paymentId.value} $settledAt")
        }
        override fun findById(paymentId: PaymentId, merchantAccount: String?): Transaction? = null
        override fun findPage(filter: TransactionFilter, offset: Int, limit: Int): List<Transaction> {
            calls.add("findPage offset=$offset limit=$limit")
            return emptyList()
        }
        override fun count(filter: TransactionFilter): Long = 0
    }

    private val repository = RecordingRepository()
    private val service = TransactionService(repository)
    private val at = Instant.parse("2026-10-02T10:15:30Z")
    private val eur = Currency("EUR")

    @Test
    fun `AUTHORIZATION saves the transaction with buyer, order, PSP reference, card and splits`() {
        val details = AuthorizationDetails(
            buyerId = "BUYER-1450",
            orderId = "ORDER-1450",
            pspReference = "psp_ref_1",
            processingModel = "MARKETPLACE",
            splits = listOf(
                PaymentSplitDto.of("BalanceAccount", "SELLER-5-1", 2900, "EUR"),
                PaymentSplitDto.of("Commission", "MARKETPLACE-5", 100, "EUR")
            ),
            cardBrand = "MASTERCARD",
            cardLast4 = "4444"
        )

        service.updateTransactions(event(JournalType.AUTHORIZATION, details))

        assertEquals(
            listOf(
                Transaction(
                    paymentId = PaymentId(1001L),
                    paymentIntentId = PaymentIntentId(231538886965329920L),
                    publicPaymentIntentId = "pi_AzaXVnPCAAA",
                    merchantAccount = "MARKETPLACE-5",
                    buyerId = BuyerId("BUYER-1450"),
                    orderId = OrderId("ORDER-1450"),
                    pspReference = "psp_ref_1",
                    processingModel = ProcessingModel.MARKETPLACE,
                    totalAmount = Amount.of(3000, eur),
                    splits = listOf(
                        PaymentSplit.of(LedgerAccountType.SELLER_PAYABLE, "SELLER-5-1", Amount.of(2900, eur)),
                        PaymentSplit.of(
                            LedgerAccountType.MERCHANT_COMMISSION_PAYABLE,
                            "MARKETPLACE-5",
                            Amount.of(100, eur)
                        )
                    ),
                    authorizedAt = at,
                    cardSummary = CardSummary.of(CardBrand.MASTERCARD, "4444")
                )
            ),
            repository.saved
        )
    }

    @Test
    fun `CAPTURE and SETTLEMENT mark the transaction, other bookings do nothing`() {
        service.updateTransactions(event(JournalType.CAPTURE, null))
        service.updateTransactions(event(JournalType.INTERNAL_TRANSFER, null))
        service.updateTransactions(event(JournalType.COMMISSION_FEE, null))
        service.updateTransactions(event(JournalType.SETTLEMENT, null))

        assertEquals(listOf("markCaptured 1001 $at", "markSettled 1001 $at"), repository.calls)
    }

    @Test
    fun `an AUTHORIZATION without payment details is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.updateTransactions(event(JournalType.AUTHORIZATION, null))
        }
        assertEquals(emptyList<String>(), repository.calls)
    }

    @Test
    fun `page and size become offset and limit, and are checked`() {
        service.findTransactions(TransactionFilter(), page = 2, size = 20)

        assertEquals(listOf("findPage offset=40 limit=20"), repository.calls)
        assertThrows(IllegalArgumentException::class.java) {
            service.findTransactions(TransactionFilter(), page = -1, size = 20)
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.findTransactions(TransactionFilter(), page = 0, size = 101)
        }
    }

    private fun event(type: JournalType, details: AuthorizationDetails?) = JournalEntriesRecorded(
        paymentIntentId = "231538886965329920",
        publicPaymentIntentId = "pi_AzaXVnPCAAA",
        merchantAccount = "MARKETPLACE-5",
        customPartitionKey = "MARKETPLACE-5",
        amountValue = 3000,
        currency = "EUR",
        ledgerBatchId = "${type.name}:pi_AzaXVnPCAAA",
        ledgerEntries = listOf(
            JournalEntryEventData.create(
                journalEntryId = "${type.name}:pi_AzaXVnPCAAA", globalJournalEntryId = 3001L, journalType = type,
                journalName = null, paymentId = 1001L, txId = 2001L, reason = null, createdAt = at,
                postings = listOf(
                    PostingEventData.create(
                        "AUTH_RECEIVABLE.MARKETPLACE-5.EUR",
                        LedgerAccountType.AUTH_RECEIVABLE,
                        3000,
                        "EUR",
                        PostingDirection.DEBIT
                    ),
                    PostingEventData.create(
                        "AUTH_LIABILITY.MARKETPLACE-5.EUR",
                        LedgerAccountType.AUTH_LIABILITY,
                        3000,
                        "EUR",
                        PostingDirection.CREDIT
                    )
                )
            )
        ),
        authorization = details,
        timestamp = at
    )
}
