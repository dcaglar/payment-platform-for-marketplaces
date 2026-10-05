package com.dogancaglar.paymentservice.util

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
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
        val authReceivable = LedgerAccount.createNew(
            LedgerAccountType.AUTH_RECEIVABLE,
            "MERCHANT-1",
            null,
            Currency("EUR")
        )
        val authLiability = LedgerAccount.createNew(
            LedgerAccountType.AUTH_LIABILITY,
            "MERCHANT-1",
            null,
            Currency("EUR")
        )
        val pId = paymentId.filter { it.isDigit() }.toLongOrNull() ?: 100L
        val result = JournalEntry.authHold(
            globalJournalEntryId = 1L,
            authTx = Tx.AuthorizationTx(
                txId = TxId(ledgerEntryId),
                paymentId = PaymentId(pId),
                paymentIntentId = PaymentIntentId(pId),
                acquirerReference = "",
                amount = amount
            ),
            journalIdentifier = paymentId,
            authReceivable = authReceivable,
            authLiability = authLiability
        )
        return result.first()
    }

    fun createCaptureJournalEntry(
        ledgerEntryId: Long,
        paymentOrderId: String = "PO-$ledgerEntryId",
        merchantId: String = "SELLER-1",
        amount: Amount = Amount.of(1000, Currency("EUR"))
    ): JournalEntry {
        val authReceivable = LedgerAccount.createNew(
            LedgerAccountType.AUTH_RECEIVABLE,
            merchantId,
            null,
            amount.currency
        )
        val authLiability = LedgerAccount.createNew(LedgerAccountType.AUTH_LIABILITY, merchantId, null, amount.currency)
        val merchantGrossPool = LedgerAccount.createNew(
            LedgerAccountType.CAPTURE_SUSPENSE,
            merchantId,
            null,
            amount.currency
        )
        val pspReceivable = LedgerAccount.createNew(LedgerAccountType.PSP_RECEIVABLE, "GLOBAL", null, amount.currency)
        val result = JournalEntry.captureGrossAsset(
            globalJournalEntryId = 2L,
            captureTx = Tx.CaptureTx(
                txId = TxId(ledgerEntryId),
                paymentId = PaymentId(100L),
                paymentIntentId = PaymentIntentId(100L),
                authorizationTxId = TxId(0L),
                acquirerReference = "",
                amount = amount
            ),
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
            println(
                "  ${it::class.simpleName?.padEnd(6)} | ${it.account.accountCode.padEnd(30)} | ${it.amount.quantity}"
            )
        }
        val net = entry.postings.sumOf { it.getSignedAmount().quantity }
        println("  -> Net = $net (should be 0)\n")
    }
}
