package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.exception.LedgerDomainException
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What each journal entry does to the balance of each ledger account. The signed amount of a posting is what the
 * balance projection applies: positive moves the account towards its normal side (a debit on an asset, a credit
 * on a liability), negative away from it.
 *
 * The running example is one €100.00 marketplace payment: €60.00 for the seller, €40.00 commission for the
 * merchant, our €3.50 platform fee (€0.50 + 3%) taken from that commission, and the PSP's €1.75 fee (€0.25 + 1.5%)
 * taken at settlement. Journal identifiers are
 * what the callers pass: internal ids, since a journal id never leaves the platform (the payment intent id, plus
 * the tx or transfer id that makes the entry unique), e.g. `CAPTURE:231538886965329920-12`.
 */
class JournalEntryTest {

    // ------------------------------------------------------------------ one entry per business event

    @Test
    fun `authorization holds the amount as a receivable from the card network and a liability to the merchant`() {
        val eur = Currency("EUR")
        val authReceivable = LedgerAccount.createNew(LedgerAccountType.AUTH_RECEIVABLE, "M-1", null, eur)
        val authLiability = LedgerAccount.createNew(LedgerAccountType.AUTH_LIABILITY, "M-1", null, eur)
        val authTx = Tx.AuthorizationTx(
            TxId(1L),
            PaymentId(100L),
            PaymentIntentId(200L),
            "psp-auth-1",
            Amount.of(10_000, eur)
        )

        val entry = JournalEntry.authHold(1L, authTx, "231538886965329920", authReceivable, authLiability).single()

        assertEquals("AUTH:231538886965329920", entry.id)
        assertEquals(JournalType.AUTHORIZATION, entry.journalType)
        assertEquals(PaymentId(100L), entry.paymentId)
        assertEquals(TxId(1L), entry.txId)
        val effects = mutableListOf<Pair<String, Long>>()
        for (posting in entry.postings) {
            effects.add(posting.account.accountCode to posting.getSignedAmount().quantity)
        }
        assertEquals(
            listOf("AUTH_RECEIVABLE.M-1.EUR" to 10_000L, "AUTH_LIABILITY.M-1.EUR" to 10_000L),
            effects
        )
    }

    @Test
    fun `capture closes the hold and replaces it with a PSP receivable and the merchant's gross pool`() {
        val eur = Currency("EUR")
        val authReceivable = LedgerAccount.createNew(LedgerAccountType.AUTH_RECEIVABLE, "M-1", null, eur)
        val authLiability = LedgerAccount.createNew(LedgerAccountType.AUTH_LIABILITY, "M-1", null, eur)
        val captureSuspense = LedgerAccount.createNew(LedgerAccountType.CAPTURE_SUSPENSE, "M-1", null, eur)
        val pspReceivable = LedgerAccount.createNew(LedgerAccountType.PSP_RECEIVABLE, "GLOBAL", null, eur)
        val captureTx = Tx.CaptureTx(
            TxId(2L),
            PaymentId(100L),
            PaymentIntentId(200L),
            TxId(1L),
            "psp-cap-1",
            Amount.of(10_000, eur)
        )

        val entry = JournalEntry.captureGrossAsset(
            2L,
            captureTx,
            "231538886965329920-12",
            Amount.of(10_000, eur),
            authReceivable,
            authLiability,
            captureSuspense,
            pspReceivable
        ).single()

        assertEquals("CAPTURE:231538886965329920-12", entry.id)
        assertEquals(JournalType.CAPTURE, entry.journalType)
        assertEquals(TxId(2L), entry.txId)
        val effects = mutableListOf<Pair<String, Long>>()
        for (posting in entry.postings) {
            effects.add(posting.account.accountCode to posting.getSignedAmount().quantity)
        }
        assertEquals(
            listOf(
                "AUTH_LIABILITY.M-1.EUR" to -10_000L,
                "AUTH_RECEIVABLE.M-1.EUR" to -10_000L,
                "PSP_RECEIVABLE.GLOBAL.EUR" to 10_000L,
                "CAPTURE_SUSPENSE.M-1.EUR" to 10_000L
            ),
            effects
        )
    }

    @Test
    fun `internal transfer moves the seller's share from the merchant's gross pool to the seller`() {
        val eur = Currency("EUR")
        val captureSuspense = LedgerAccount.createNew(LedgerAccountType.CAPTURE_SUSPENSE, "M-1", null, eur)
        val sellerPayable = LedgerAccount.createNew(LedgerAccountType.SELLER_PAYABLE, "M-1", "S-1", eur)

        val entry = JournalEntry.internalTransfer(
            3L,
            PaymentId(100L),
            "231538886965329920-45",
            Amount.of(6_000, eur),
            captureSuspense,
            sellerPayable,
            "MARKETPLACE_SELLER_SPLIT"
        ).single()

        assertEquals("INTERNAL_TRANSFER:231538886965329920-45", entry.id)
        assertEquals(JournalType.INTERNAL_TRANSFER, entry.journalType)
        assertEquals("MARKETPLACE_SELLER_SPLIT", entry.reason)
        assertNull(entry.txId) // no PSP interaction behind it
        val effects = mutableListOf<Pair<String, Long>>()
        for (posting in entry.postings) {
            effects.add(posting.account.accountCode to posting.getSignedAmount().quantity)
        }
        assertEquals(
            listOf("CAPTURE_SUSPENSE.M-1.EUR" to -6_000L, "SELLER_PAYABLE.M-1.S-1.EUR" to 6_000L),
            effects
        )
    }

    @Test
    fun `platform fee moves from what we owe the merchant into the merchant's fee reserve`() {
        val eur = Currency("EUR")
        val commissionPayable = LedgerAccount.createNew(LedgerAccountType.MERCHANT_COMMISSION_PAYABLE, "M-1", null, eur)
        val feeReserve = LedgerAccount.createNew(LedgerAccountType.PLATFORM_FEE_RESERVE, "M-1", null, eur)

        val entry = JournalEntry.commissionFeeRegistered(
            4L,
            PaymentId(100L),
            "231538886965329920-46",
            Amount.of(350, eur),
            feeReserve,
            commissionPayable
        ).single()

        assertEquals("MOR_DC_COMMISSION:231538886965329920-46", entry.id)
        assertEquals(JournalType.COMMISSION_FEE, entry.journalType)
        assertNull(entry.txId)
        val effects = mutableListOf<Pair<String, Long>>()
        for (posting in entry.postings) {
            effects.add(posting.account.accountCode to posting.getSignedAmount().quantity)
        }
        assertEquals(
            listOf("MERCHANT_COMMISSION_PAYABLE.M-1.EUR" to -350L, "PLATFORM_FEE_RESERVE.M-1.EUR" to 350L),
            effects
        )
    }

    @Test
    fun `platform fee can only be reserved in a fee reserve account`() {
        val eur = Currency("EUR")
        val commissionPayable = LedgerAccount.createNew(LedgerAccountType.MERCHANT_COMMISSION_PAYABLE, "M-1", null, eur)
        val notAReserve = LedgerAccount.createNew(LedgerAccountType.CAPTURE_SUSPENSE, "M-1", null, eur)

        assertFailsWith<LedgerDomainException.InvariantViolationException> {
            JournalEntry.commissionFeeRegistered(
                4L,
                PaymentId(100L),
                "231538886965329920-46",
                Amount.of(350, eur),
                notAReserve,
                commissionPayable
            )
        }
    }

    @Test
    fun `settlement turns the PSP receivable into cash in the bank and the PSP fee`() {
        val eur = Currency("EUR")
        val platformCash = LedgerAccount.createNew(LedgerAccountType.PLATFORM_CASH, "GLOBAL", null, eur)
        val pspReceivable = LedgerAccount.createNew(LedgerAccountType.PSP_RECEIVABLE, "GLOBAL", null, eur)
        val pspFeeExpense = LedgerAccount.createNew(LedgerAccountType.PSP_FEE_EXPENSE, "GLOBAL", null, eur)
        val captureTx = Tx.CaptureTx(
            TxId(2L),
            PaymentId(100L),
            PaymentIntentId(200L),
            TxId(1L),
            "psp-cap-1",
            Amount.of(10_000, eur)
        )
        val settleTx = Tx.createSettleTx(TxId(5L), captureTx, "psp-batch-1", Amount.of(10_000, eur))

        val entry = JournalEntry.settlementLineItem(
            5L,
            settleTx,
            "SDR_RECON_LN_15",
            Amount.of(9_825, eur),
            Amount.of(175, eur),
            platformCash,
            pspReceivable,
            pspFeeExpense
        ).single()

        assertEquals("SETTLE:SDR_RECON_LN_15", entry.id)
        assertEquals(JournalType.SETTLEMENT, entry.journalType)
        assertEquals(TxId(5L), entry.txId)
        val effects = mutableListOf<Pair<String, Long>>()
        for (posting in entry.postings) {
            effects.add(posting.account.accountCode to posting.getSignedAmount().quantity)
        }
        assertEquals(
            listOf(
                "PLATFORM_CASH.GLOBAL.EUR" to 9_825L,
                "PSP_FEE_EXPENSE.GLOBAL.EUR" to 175L,
                "PSP_RECEIVABLE.GLOBAL.EUR" to -10_000L
            ),
            effects
        )
    }

    @Test
    fun `settlement whose cash and fee do not add up to the gross amount is rejected`() {
        val eur = Currency("EUR")
        val platformCash = LedgerAccount.createNew(LedgerAccountType.PLATFORM_CASH, "GLOBAL", null, eur)
        val pspReceivable = LedgerAccount.createNew(LedgerAccountType.PSP_RECEIVABLE, "GLOBAL", null, eur)
        val pspFeeExpense = LedgerAccount.createNew(LedgerAccountType.PSP_FEE_EXPENSE, "GLOBAL", null, eur)
        val captureTx = Tx.CaptureTx(
            TxId(2L),
            PaymentId(100L),
            PaymentIntentId(200L),
            TxId(1L),
            "psp-cap-1",
            Amount.of(10_000, eur)
        )
        val settleTx = Tx.createSettleTx(TxId(5L), captureTx, "psp-batch-1", Amount.of(10_000, eur))

        assertFailsWith<LedgerDomainException.UnbalancedJournalEntryException> {
            JournalEntry.settlementLineItem(
                5L,
                settleTx,
                "SDR_RECON_LN_15",
                Amount.of(9_825, eur),
                Amount.of(100, eur), // 9,825 + 100 is not 10,000
                platformCash,
                pspReceivable,
                pspFeeExpense
            )
        }
    }

    @Test
    fun `revenue recognition releases the matured fee reserve into platform revenue`() {
        val eur = Currency("EUR")
        val feeReserve = LedgerAccount.createNew(LedgerAccountType.PLATFORM_FEE_RESERVE, "M-1", null, eur)
        val platformRevenue = LedgerAccount.createNew(LedgerAccountType.PLATFORM_REVENUE, "GLOBAL", null, eur)

        val entry = JournalEntry.recognizePlatformRevenue(
            6L,
            "2026-10-M-1",
            Amount.of(350, eur),
            feeReserve,
            platformRevenue
        )
            .single()

        assertEquals("REV_REC:2026-10-M-1", entry.id)
        assertEquals(JournalType.REVENUE_RECOGNITION, entry.journalType)
        assertEquals(PaymentId(0L), entry.paymentId) // a batch over many payments, not one
        assertNull(entry.txId)
        val effects = mutableListOf<Pair<String, Long>>()
        for (posting in entry.postings) {
            effects.add(posting.account.accountCode to posting.getSignedAmount().quantity)
        }
        assertEquals(
            listOf("PLATFORM_FEE_RESERVE.M-1.EUR" to -350L, "PLATFORM_REVENUE.GLOBAL.EUR" to 350L),
            effects
        )
    }

    @Test
    fun `revenue recognition only goes from a fee reserve to platform revenue`() {
        val eur = Currency("EUR")
        val feeReserve = LedgerAccount.createNew(LedgerAccountType.PLATFORM_FEE_RESERVE, "M-1", null, eur)
        val platformRevenue = LedgerAccount.createNew(LedgerAccountType.PLATFORM_REVENUE, "GLOBAL", null, eur)
        val platformCash = LedgerAccount.createNew(LedgerAccountType.PLATFORM_CASH, "GLOBAL", null, eur)
        val commissionPayable = LedgerAccount.createNew(LedgerAccountType.MERCHANT_COMMISSION_PAYABLE, "M-1", null, eur)

        assertFailsWith<LedgerDomainException.InvariantViolationException> {
            JournalEntry.recognizePlatformRevenue(
                6L,
                "2026-10-M-1",
                Amount.of(350, eur),
                commissionPayable,
                platformRevenue
            )
        }
        assertFailsWith<LedgerDomainException.InvariantViolationException> {
            JournalEntry.recognizePlatformRevenue(6L, "2026-10-M-1", Amount.of(350, eur), feeReserve, platformCash)
        }
    }

    @Test
    fun `payout settles what we owe the seller with cash from the bank`() {
        val eur = Currency("EUR")
        val sellerPayable = LedgerAccount.createNew(LedgerAccountType.SELLER_PAYABLE, "M-1", "S-1", eur)
        val platformCash = LedgerAccount.createNew(LedgerAccountType.PLATFORM_CASH, "GLOBAL", null, eur)
        val payoutTx = Tx.PayoutTx(
            TxId(7L),
            PaymentId(100L),
            PaymentIntentId(200L),
            "S-1",
            "payout-batch-1",
            Amount.of(6_000, eur)
        )

        val entry = JournalEntry.payout(
            7L,
            payoutTx,
            "PAYOUT_BATCH_2026-10-16-S-1",
            sellerPayable,
            platformCash
        ).single()

        assertEquals("PAYOUT:PAYOUT_BATCH_2026-10-16-S-1", entry.id)
        assertEquals(JournalType.PAYOUT, entry.journalType)
        assertEquals(TxId(7L), entry.txId)
        val effects = mutableListOf<Pair<String, Long>>()
        for (posting in entry.postings) {
            effects.add(posting.account.accountCode to posting.getSignedAmount().quantity)
        }
        assertEquals(
            listOf("SELLER_PAYABLE.M-1.S-1.EUR" to -6_000L, "PLATFORM_CASH.GLOBAL.EUR" to -6_000L),
            effects
        )
    }

    @Test
    fun `refund takes the amount back out of the merchant's gross pool and the PSP receivable`() {
        val eur = Currency("EUR")
        val captureSuspense = LedgerAccount.createNew(LedgerAccountType.CAPTURE_SUSPENSE, "M-1", null, eur)
        val pspReceivable = LedgerAccount.createNew(LedgerAccountType.PSP_RECEIVABLE, "GLOBAL", null, eur)
        val captureTx = Tx.CaptureTx(
            TxId(2L),
            PaymentId(100L),
            PaymentIntentId(200L),
            TxId(1L),
            "psp-cap-1",
            Amount.of(10_000, eur)
        )
        val refundTx = Tx.createRefundTx(TxId(8L), captureTx, "psp-refund-1", Amount.of(10_000, eur))

        val entry = JournalEntry.refund(
            8L,
            refundTx,
            "231538886965329920-18",
            captureSuspense,
            pspReceivable
        ).single()

        assertEquals("REFUND:231538886965329920-18", entry.id)
        assertEquals(JournalType.REFUND, entry.journalType)
        assertEquals(TxId(8L), entry.txId)
        val effects = mutableListOf<Pair<String, Long>>()
        for (posting in entry.postings) {
            effects.add(posting.account.accountCode to posting.getSignedAmount().quantity)
        }
        assertEquals(
            listOf("CAPTURE_SUSPENSE.M-1.EUR" to -10_000L, "PSP_RECEIVABLE.GLOBAL.EUR" to -10_000L),
            effects
        )
    }

    // ------------------------------------------------------------------ what no entry may do

    @Test
    fun `an entry cannot post to the same account twice`() {
        val eur = Currency("EUR")
        val captureSuspense = LedgerAccount.createNew(LedgerAccountType.CAPTURE_SUSPENSE, "M-1", null, eur)

        assertFailsWith<LedgerDomainException.DuplicateAccountInJournalException> {
            JournalEntry.internalTransfer(
                3L,
                PaymentId(100L),
                "231538886965329920-45",
                Amount.of(6_000, eur),
                captureSuspense,
                captureSuspense
            )
        }
    }

    @Test
    fun `an entry needs a positive global journal entry id`() {
        val eur = Currency("EUR")
        val authReceivable = LedgerAccount.createNew(LedgerAccountType.AUTH_RECEIVABLE, "M-1", null, eur)
        val authLiability = LedgerAccount.createNew(LedgerAccountType.AUTH_LIABILITY, "M-1", null, eur)
        val authTx = Tx.AuthorizationTx(
            TxId(1L),
            PaymentId(100L),
            PaymentIntentId(200L),
            "psp-auth-1",
            Amount.of(10_000, eur)
        )

        assertFailsWith<LedgerDomainException.InvariantViolationException> {
            JournalEntry.authHold(0L, authTx, "231538886965329920", authReceivable, authLiability)
        }
    }
}
