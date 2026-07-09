package com.dogancaglar.paymentservice.util

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.*
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId

/**
 * Domain-consistent factory for creating valid JournalEntry test data.
 */
object JournalEntryTestHelper {

    fun createAuthHoldJournalEntry(
        ledgerEntryId: Long,
        paymentId: String = "PO-$ledgerEntryId",
        amount: Amount
    ): JournalEntry {
        val authReceivable = Account.create(AccountType.AUTH_RECEIVABLE, "GLOBAL")
        val authLiability = Account.create(AccountType.AUTH_LIABILITY, "GLOBAL")
        val pId = paymentId.filter { it.isDigit() }.toLongOrNull() ?: 100L
        val result = JournalEntry.authHold(
            globalJournalEntryId = 1L,
            paymentId = PaymentId(pId),
            txId = TxId(ledgerEntryId),
            journalIdentifier = paymentId,
            authorizedAmount = amount,
            authReceivable = authReceivable,
            authLiability = authLiability
        )
        return result.first()
    }

    fun createCaptureJournalEntry(
        ledgerEntryId: Long,
        paymentOrderId: String = "PO-$ledgerEntryId",
        merchantId: String = "SELLER-1",
        amount: Amount = Amount.of(1000, Currency( "EUR"))
    ): JournalEntry {
        val authReceivable = Account.create(AccountType.AUTH_RECEIVABLE, "GLOBAL")
        val authLiability = Account.create(AccountType.AUTH_LIABILITY, "GLOBAL")
        val merchantGrossPool = Account.create(AccountType.MERCHANT_GROSS_CAPTURE_SUSPENSE, merchantId)
        val pspReceivable = Account.create(AccountType.PSP_RECEIVABLES, "GLOBAL")
        val poId = paymentOrderId.filter { it.isDigit() }.toLongOrNull() ?: 200L
        val result = JournalEntry.captureGrossAsset(
            globalJournalEntryId = 2L,
            paymentId = PaymentId(100L),
            txId = TxId(ledgerEntryId),
            journalIdentifier = paymentOrderId,
            capturedAmount = amount,
            authReceivable = authReceivable,
            authLiability = authLiability,
            merchantGrossPool = merchantGrossPool,
            pspReceivable = pspReceivable
        )
        return result.first()
    }

    fun printEntry(entry: JournalEntry) {
        println("JournalEntry(id=${entry.id}, type=${entry.journalType})")
        entry.postings.forEach {
            println("  ${it::class.simpleName?.padEnd(6)} | ${it.account.accountCode.padEnd(30)} | ${it.amount.quantity}")
        }
        val net = entry.postings.sumOf { it.getSignedAmount().quantity }
        println("  -> Net = $net (should be 0)\n")
    }
}