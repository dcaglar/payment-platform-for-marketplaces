package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The balances after a payment's whole life: the journal entries of one payment recorded in order and summed per
 * ledger account. Same running example as JournalEntryTest: €100.00, our €3.50 fee, the PSP's €1.75 fee.
 */
class JournalEntryLifecycleTest {

    @Test
    fun `after authorization, capture, splits, fees, settlement, revenue recognition and payout the books add up`() {
        val eur = Currency("EUR")
        val authReceivable = LedgerAccount.createNew(LedgerAccountType.AUTH_RECEIVABLE, "M-1", null, eur)
        val authLiability = LedgerAccount.createNew(LedgerAccountType.AUTH_LIABILITY, "M-1", null, eur)
        val captureSuspense = LedgerAccount.createNew(LedgerAccountType.CAPTURE_SUSPENSE, "M-1", null, eur)
        val sellerPayable = LedgerAccount.createNew(LedgerAccountType.SELLER_PAYABLE, "M-1", "S-1", eur)
        val commissionPayable = LedgerAccount.createNew(LedgerAccountType.MERCHANT_COMMISSION_PAYABLE, "M-1", null, eur)
        val feeReserve = LedgerAccount.createNew(LedgerAccountType.PLATFORM_FEE_RESERVE, "M-1", null, eur)
        val pspReceivable = LedgerAccount.createNew(LedgerAccountType.PSP_RECEIVABLE, "GLOBAL", null, eur)
        val pspFeeExpense = LedgerAccount.createNew(LedgerAccountType.PSP_FEE_EXPENSE, "GLOBAL", null, eur)
        val platformCash = LedgerAccount.createNew(LedgerAccountType.PLATFORM_CASH, "GLOBAL", null, eur)
        val platformRevenue = LedgerAccount.createNew(LedgerAccountType.PLATFORM_REVENUE, "GLOBAL", null, eur)
        val paymentId = PaymentId(100L)
        val paymentIntentId = PaymentIntentId(200L)
        val authTx = Tx.AuthorizationTx(TxId(1L), paymentId, paymentIntentId, "psp-auth-1", Amount.of(10_000, eur))
        val captureTx = Tx.CaptureTx(
            TxId(2L),
            paymentId,
            paymentIntentId,
            TxId(1L),
            "psp-cap-1",
            Amount.of(10_000, eur)
        )
        val settleTx = Tx.createSettleTx(TxId(5L), captureTx, "psp-batch-1", Amount.of(10_000, eur))
        val payoutTx = Tx.PayoutTx(TxId(7L), paymentId, paymentIntentId, "S-1", "payout-batch-1", Amount.of(6_000, eur))

        val entries = mutableListOf<JournalEntry>()
        entries.addAll(JournalEntry.authHold(1L, authTx, "231538886965329920", authReceivable, authLiability))
        entries.addAll(
            JournalEntry.captureGrossAsset(
                2L,
                captureTx,
                "231538886965329920-12",
                Amount.of(10_000, eur),
                authReceivable,
                authLiability,
                captureSuspense,
                pspReceivable
            )
        )
        entries.addAll(
            JournalEntry.internalTransfer(
                3L,
                paymentId,
                "231538886965329920-45",
                Amount.of(6_000, eur),
                captureSuspense,
                sellerPayable
            )
        )
        entries.addAll(
            JournalEntry.internalTransfer(
                4L,
                paymentId,
                "231538886965329920-46",
                Amount.of(4_000, eur),
                captureSuspense,
                commissionPayable
            )
        )
        entries.addAll(
            JournalEntry.commissionFeeRegistered(
                5L,
                paymentId,
                "231538886965329920-46",
                Amount.of(350, eur),
                feeReserve,
                commissionPayable
            )
        )
        entries.addAll(
            JournalEntry.settlementLineItem(
                6L,
                settleTx,
                "SDR_RECON_LN_15",
                Amount.of(9_825, eur),
                Amount.of(175, eur),
                platformCash,
                pspReceivable,
                pspFeeExpense
            )
        )
        entries.addAll(
            JournalEntry.recognizePlatformRevenue(7L, "2026-10-M-1", Amount.of(350, eur), feeReserve, platformRevenue)
        )
        entries.addAll(JournalEntry.payout(8L, payoutTx, "PAYOUT_BATCH_2026-10-16-S-1", sellerPayable, platformCash))

        val balances = LinkedHashMap<String, Long>()
        for (entry in entries) {
            for (posting in entry.postings) {
                val code = posting.account.accountCode
                balances[code] = (balances[code] ?: 0L) + posting.getSignedAmount().quantity
            }
        }

        // the hold and the gross pool are fully released, the PSP has paid, the seller has been paid
        assertEquals(0L, balances["AUTH_RECEIVABLE.M-1.EUR"])
        assertEquals(0L, balances["AUTH_LIABILITY.M-1.EUR"])
        assertEquals(0L, balances["CAPTURE_SUSPENSE.M-1.EUR"])
        assertEquals(0L, balances["PSP_RECEIVABLE.GLOBAL.EUR"])
        assertEquals(0L, balances["SELLER_PAYABLE.M-1.S-1.EUR"])
        assertEquals(0L, balances["PLATFORM_FEE_RESERVE.M-1.EUR"])
        // what is left: the merchant's commission minus our fee, our fee as revenue, the PSP fee as expense,
        // and the cash: 100 settled - 1.75 PSP fee - 60 paid out
        assertEquals(3_650L, balances["MERCHANT_COMMISSION_PAYABLE.M-1.EUR"])
        assertEquals(350L, balances["PLATFORM_REVENUE.GLOBAL.EUR"])
        assertEquals(175L, balances["PSP_FEE_EXPENSE.GLOBAL.EUR"])
        assertEquals(3_825L, balances["PLATFORM_CASH.GLOBAL.EUR"])
    }

    @Test
    fun `with two sellers each gets its own share and the gross pool still ends at zero`() {
        val eur = Currency("EUR")
        val authReceivable = LedgerAccount.createNew(LedgerAccountType.AUTH_RECEIVABLE, "M-1", null, eur)
        val authLiability = LedgerAccount.createNew(LedgerAccountType.AUTH_LIABILITY, "M-1", null, eur)
        val captureSuspense = LedgerAccount.createNew(LedgerAccountType.CAPTURE_SUSPENSE, "M-1", null, eur)
        val sellerOnePayable = LedgerAccount.createNew(LedgerAccountType.SELLER_PAYABLE, "M-1", "S-1", eur)
        val sellerTwoPayable = LedgerAccount.createNew(LedgerAccountType.SELLER_PAYABLE, "M-1", "S-2", eur)
        val commissionPayable = LedgerAccount.createNew(LedgerAccountType.MERCHANT_COMMISSION_PAYABLE, "M-1", null, eur)
        val feeReserve = LedgerAccount.createNew(LedgerAccountType.PLATFORM_FEE_RESERVE, "M-1", null, eur)
        val pspReceivable = LedgerAccount.createNew(LedgerAccountType.PSP_RECEIVABLE, "GLOBAL", null, eur)
        val pspFeeExpense = LedgerAccount.createNew(LedgerAccountType.PSP_FEE_EXPENSE, "GLOBAL", null, eur)
        val platformCash = LedgerAccount.createNew(LedgerAccountType.PLATFORM_CASH, "GLOBAL", null, eur)
        val platformRevenue = LedgerAccount.createNew(LedgerAccountType.PLATFORM_REVENUE, "GLOBAL", null, eur)
        val paymentId = PaymentId(100L)
        val paymentIntentId = PaymentIntentId(200L)
        val authTx = Tx.AuthorizationTx(TxId(1L), paymentId, paymentIntentId, "psp-auth-1", Amount.of(10_000, eur))
        val captureTx = Tx.CaptureTx(
            TxId(2L),
            paymentId,
            paymentIntentId,
            TxId(1L),
            "psp-cap-1",
            Amount.of(10_000, eur)
        )
        val settleTx = Tx.createSettleTx(TxId(5L), captureTx, "psp-batch-1", Amount.of(10_000, eur))
        val payoutOne = Tx.PayoutTx(
            TxId(7L),
            paymentId,
            paymentIntentId,
            "S-1",
            "payout-batch-1",
            Amount.of(4_500, eur)
        )
        val payoutTwo = Tx.PayoutTx(
            TxId(8L),
            paymentId,
            paymentIntentId,
            "S-2",
            "payout-batch-1",
            Amount.of(2_500, eur)
        )

        // €100: €45 for S-1, €25 for S-2, €30 commission for the merchant
        val entries = mutableListOf<JournalEntry>()
        entries.addAll(JournalEntry.authHold(1L, authTx, "231538886965329920", authReceivable, authLiability))
        entries.addAll(
            JournalEntry.captureGrossAsset(
                2L,
                captureTx,
                "231538886965329920-12",
                Amount.of(10_000, eur),
                authReceivable,
                authLiability,
                captureSuspense,
                pspReceivable
            )
        )
        entries.addAll(
            JournalEntry.internalTransfer(
                3L,
                paymentId,
                "231538886965329920-45",
                Amount.of(4_500, eur),
                captureSuspense,
                sellerOnePayable
            )
        )
        entries.addAll(
            JournalEntry.internalTransfer(
                4L,
                paymentId,
                "231538886965329920-46",
                Amount.of(2_500, eur),
                captureSuspense,
                sellerTwoPayable
            )
        )
        entries.addAll(
            JournalEntry.internalTransfer(
                5L,
                paymentId,
                "231538886965329920-47",
                Amount.of(3_000, eur),
                captureSuspense,
                commissionPayable
            )
        )
        entries.addAll(
            JournalEntry.commissionFeeRegistered(
                6L,
                paymentId,
                "231538886965329920-47",
                Amount.of(350, eur),
                feeReserve,
                commissionPayable
            )
        )
        entries.addAll(
            JournalEntry.settlementLineItem(
                7L,
                settleTx,
                "SDR_RECON_LN_15",
                Amount.of(9_825, eur),
                Amount.of(175, eur),
                platformCash,
                pspReceivable,
                pspFeeExpense
            )
        )
        entries.addAll(
            JournalEntry.recognizePlatformRevenue(8L, "2026-10-M-1", Amount.of(350, eur), feeReserve, platformRevenue)
        )
        entries.addAll(
            JournalEntry.payout(9L, payoutOne, "PAYOUT_BATCH_2026-10-16-S-1", sellerOnePayable, platformCash)
        )
        entries.addAll(
            JournalEntry.payout(10L, payoutTwo, "PAYOUT_BATCH_2026-10-16-S-2", sellerTwoPayable, platformCash)
        )

        val balances = LinkedHashMap<String, Long>()
        for (entry in entries) {
            for (posting in entry.postings) {
                val code = posting.account.accountCode
                balances[code] = (balances[code] ?: 0L) + posting.getSignedAmount().quantity
            }
        }

        // the pool is drained by the three splits, and each seller's payout clears only its own payable
        assertEquals(0L, balances["CAPTURE_SUSPENSE.M-1.EUR"])
        assertEquals(0L, balances["SELLER_PAYABLE.M-1.S-1.EUR"])
        assertEquals(0L, balances["SELLER_PAYABLE.M-1.S-2.EUR"])
        assertEquals(0L, balances["AUTH_RECEIVABLE.M-1.EUR"])
        assertEquals(0L, balances["AUTH_LIABILITY.M-1.EUR"])
        assertEquals(0L, balances["PSP_RECEIVABLE.GLOBAL.EUR"])
        assertEquals(0L, balances["PLATFORM_FEE_RESERVE.M-1.EUR"])
        // what is left: 30 commission - 3.50 our fee; our fee as revenue; the PSP fee; cash 98.25 - 45 - 25
        assertEquals(2_650L, balances["MERCHANT_COMMISSION_PAYABLE.M-1.EUR"])
        assertEquals(350L, balances["PLATFORM_REVENUE.GLOBAL.EUR"])
        assertEquals(175L, balances["PSP_FEE_EXPENSE.GLOBAL.EUR"])
        assertEquals(2_825L, balances["PLATFORM_CASH.GLOBAL.EUR"])
    }

    @Test
    fun `a captured payment that is refunded in full leaves no balance anywhere`() {
        val eur = Currency("EUR")
        val authReceivable = LedgerAccount.createNew(LedgerAccountType.AUTH_RECEIVABLE, "M-1", null, eur)
        val authLiability = LedgerAccount.createNew(LedgerAccountType.AUTH_LIABILITY, "M-1", null, eur)
        val captureSuspense = LedgerAccount.createNew(LedgerAccountType.CAPTURE_SUSPENSE, "M-1", null, eur)
        val pspReceivable = LedgerAccount.createNew(LedgerAccountType.PSP_RECEIVABLE, "GLOBAL", null, eur)
        val paymentId = PaymentId(100L)
        val paymentIntentId = PaymentIntentId(200L)
        val authTx = Tx.AuthorizationTx(TxId(1L), paymentId, paymentIntentId, "psp-auth-1", Amount.of(10_000, eur))
        val captureTx = Tx.CaptureTx(
            TxId(2L),
            paymentId,
            paymentIntentId,
            TxId(1L),
            "psp-cap-1",
            Amount.of(10_000, eur)
        )
        val refundTx = Tx.createRefundTx(TxId(8L), captureTx, "psp-refund-1", Amount.of(10_000, eur))

        val entries = mutableListOf<JournalEntry>()
        entries.addAll(JournalEntry.authHold(1L, authTx, "231538886965329920", authReceivable, authLiability))
        entries.addAll(
            JournalEntry.captureGrossAsset(
                2L,
                captureTx,
                "231538886965329920-12",
                Amount.of(10_000, eur),
                authReceivable,
                authLiability,
                captureSuspense,
                pspReceivable
            )
        )
        entries.addAll(
            JournalEntry.refund(3L, refundTx, "231538886965329920-18", captureSuspense, pspReceivable)
        )

        val balances = LinkedHashMap<String, Long>()
        for (entry in entries) {
            for (posting in entry.postings) {
                val code = posting.account.accountCode
                balances[code] = (balances[code] ?: 0L) + posting.getSignedAmount().quantity
            }
        }

        assertEquals(0L, balances["AUTH_RECEIVABLE.M-1.EUR"])
        assertEquals(0L, balances["AUTH_LIABILITY.M-1.EUR"])
        assertEquals(0L, balances["CAPTURE_SUSPENSE.M-1.EUR"])
        assertEquals(0L, balances["PSP_RECEIVABLE.GLOBAL.EUR"])
    }
}
