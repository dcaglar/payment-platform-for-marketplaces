package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JournalEntryTest — The Mor-DC Ledger Textbook
 *
 * This test suite validates the core double-entry accounting invariants of the system.
 * * Golden Rules of Double-Entry Bookkeeping enforced here:
 * 1. ASSETS & EXPENSES: Normal balance is DEBIT. (Debit increases them, Credit decreases them).
 * 2. LIABILITIES, EQUITY & REVENUE: Normal balance is CREDIT. (Credit increases them, Debit decreases them).
 * 3. Every transaction MUST balance: Total Debits == Total Credits.
 */
class JournalEntryTest {

    private val eur = Currency("EUR")
    private val paymentId = PaymentId(100L)
    private val txId = TxId(1L)
    private val journalId = "PAY-1"

    // === MOCK ACCOUNT DIRECTORY ===
    // Assets (Normal Balance: DEBIT)
    private val platformCashAccount = Account.create(AccountType.PLATFORM_CASH, "PLATFORM_CASH.GLOBAL.EUR")
    private val pspReceivableAccount = Account.create(AccountType.PSP_RECEIVABLES, "PSP_RECEIVABLES.GLOBAL.EUR")
    private val authReceivableAccount = Account.create(AccountType.AUTH_RECEIVABLE, "AUTH_RECEIVABLE.GLOBAL.EUR")

    // Expenses (Normal Balance: DEBIT)
    private val pspFeeExpenseAccount = Account.create(AccountType.PSP_FEE_EXPENSE, "PSP_FEE_EXPENSE.GLOBAL.EUR")

    // Liabilities (Normal Balance: CREDIT)
    private val authLiabilityAccount = Account.create(AccountType.AUTH_LIABILITY, "AUTH_LIABILITY.GLOBAL.EUR")
    private val merchantSuspenseAccount = Account.create(AccountType.MERCHANT_GROSS_CAPTURE_SUSPENSE, "SUSPENSE.M-1.EUR")
    private val operatorCommissionAccount = Account.create(AccountType.MARKETPLACE_COMMISSION_REVENUE_BALANCE_ACCOUNT, "COMMISSION.M-1.EUR")
    private val subSellerAccount = Account.create(AccountType.MARKETPLACE_SELLER_BALANCE_ACCOUNT, "SELLER.S-1.EUR")
    private val commissionEscrowAccount = Account.create(AccountType.PLATFORM_COMMISSION_ESCROW, "ESCROW.M-1.EUR")

    // Revenue (Normal Balance: CREDIT)
    private val platformOperationalRevenueAccount = Account.create(AccountType.PLATFORM_OPERATIONAL_REVENUE, "REVENUE.GLOBAL.EUR")

    // === HELPER ASSERTIONS ===

    private fun assertBalanced(entry: JournalEntry) {
        val totalDebits = entry.postings.filterIsInstance<Posting.Debit>().sumOf { it.amount.quantity }
        val totalCredits = entry.postings.filterIsInstance<Posting.Credit>().sumOf { it.amount.quantity }
        assertEquals(totalDebits, totalCredits, "CRITICAL LEDGER FAILURE: Journal entry [${entry.id}] is not balanced. Debits: $totalDebits, Credits: $totalCredits")
        assertTrue(totalDebits > 0, "Journal entry has zero financial movement.")
    }

    private fun assertPostingContains(entry: JournalEntry, account: Account, isDebit: Boolean, expectedAmount: Long) {
        val matchingPostings = entry.postings.filter { it.account == account && ((isDebit && it is Posting.Debit) || (!isDebit && it is Posting.Credit)) }
        assertTrue(matchingPostings.isNotEmpty(), "Expected ${if(isDebit) "Debit (DR)" else "Credit (CR)"} for account ${account.type} was not found.")
        assertEquals(expectedAmount, matchingPostings.sumOf { it.amount.quantity }, "Financial miscalculation for account ${account.accountCode}")
    }

    // =========================================================================
    // THE TESTS (EXECUTABLE DOCUMENTATION)
    // =========================================================================

    @Test
    fun `authHold - reserves customer funds without moving real cash`() {
        /*
         * SCENARIO: Customer clicks "Pay". Bank approves €100 hold. No physical money has moved yet.
         * * RULE:
         * - DR Auth Receivable (+Asset): We expect €100 from the card network eventually.
         * - CR Auth Liability (+Liability): We owe €100 to the merchant once it clears.
         */
        val amount = Amount.of(10_000, eur) // €100.00

        val entries = JournalEntry.authHold(1L, paymentId, txId, journalId, amount, authReceivableAccount, authLiabilityAccount)
        val entry = entries.first()

        assertBalanced(entry)
        assertEquals(JournalType.AUTHORIZATION, entry.journalType)
        assertPostingContains(entry, authReceivableAccount, isDebit = true, expectedAmount = 10_000)
        assertPostingContains(entry, authLiabilityAccount, isDebit = false, expectedAmount = 10_000)
    }

    @Test
    fun `captureGrossAsset - finalizes the sale and opens real receivables`() {
        /*
         * SCENARIO: The platform captures the authorized funds. Adyen confirms the capture.
         * * RULE:
         * - DR Auth Liability (-Liability): Erase the temporary hold liability.
         * - CR Auth Receivable (-Asset): Erase the temporary hold asset.
         * - DR PSP Receivable (+Asset): Adyen now legally owes us real cash.
         * - CR Merchant Suspense (+Liability): We officially owe this gross money to the merchant ecosystem.
         */
        val amount = Amount.of(10_000, eur)

        val entries = JournalEntry.captureGrossAsset(2L, paymentId, txId, journalId, amount, authReceivableAccount, authLiabilityAccount, merchantSuspenseAccount, pspReceivableAccount)
        val entry = entries.first()

        assertBalanced(entry)
        assertEquals(JournalType.CAPTURE, entry.journalType)
        assertPostingContains(entry, authLiabilityAccount, isDebit = true, expectedAmount = 10_000)
        assertPostingContains(entry, authReceivableAccount, isDebit = false, expectedAmount = 10_000)
        assertPostingContains(entry, pspReceivableAccount, isDebit = true, expectedAmount = 10_000)
        assertPostingContains(entry, merchantSuspenseAccount, isDebit = false, expectedAmount = 10_000)
    }

    @Test
    fun `internalTransfer - shifts liability balances inside the platform`() {
        /*
         * SCENARIO: Outbox consumer distributes €50 from suspense to a specific sub-seller's wallet.
         * * RULE:
         * - DR Suspense Pool (-Liability): We no longer owe this generic pool.
         * - CR Seller Balance (+Liability): We now owe this specific sub-seller.
         */
        val amount = Amount.of(5_000, eur) // €50.00

        val entries = JournalEntry.internalTransfer(3L, paymentId,  journalId, amount, merchantSuspenseAccount, subSellerAccount,"ts")
        val entry = entries.first()

        assertBalanced(entry)
        assertEquals(JournalType.INTERNAL_TRANSFER, entry.journalType)
        assertPostingContains(entry, merchantSuspenseAccount, isDebit = true, expectedAmount = 5_000)
        assertPostingContains(entry, subSellerAccount, isDebit = false, expectedAmount = 5_000)
    }

    @Test
    fun `settlementLineItem - reconciles incoming physical cash and records processing fees`() {
        /*
         * SCENARIO: Adyen sends the daily settlement CSV. A €100 capture arrived, but Adyen took €5 in fees.
         * We receive €95 in our physical bank account.
         * * RULE:
         * - DR Platform Cash (+Asset): Our physical bank balance increases by €95.
         * - DR PSP Fee Expense (+Expense): We record a €5 cost of doing business.
         * - CR PSP Receivable (-Asset): We wipe out the entire €100 IOU from Adyen.
         */
        val gross = Amount.of(10_000, eur)
        val settledAmount = Amount.of(9_500, eur)
        val feeAmount = Amount.of(500, eur)

        val entries = JournalEntry.settlementLineItem(4L, paymentId, txId, journalId, gross, settledAmount, feeAmount, platformCashAccount, pspReceivableAccount, pspFeeExpenseAccount)
        val entry = entries.first()

        assertBalanced(entry)
        assertEquals(JournalType.SETTLEMENT, entry.journalType)
        assertPostingContains(entry, platformCashAccount, isDebit = true, expectedAmount = 9_500)
        assertPostingContains(entry, pspFeeExpenseAccount, isDebit = true, expectedAmount = 500)
        assertPostingContains(entry, pspReceivableAccount, isDebit = false, expectedAmount = 10_000)
    }

    @Test
    fun `commissionFeeRegistered - isolates Mor-DC's infrastructure cut into safety escrow`() {
        /*
         * SCENARIO: Mor-DC charges a €2 platform fee to the marketplace operator.
         * * RULE:
         * - DR Operator Commission Account (-Liability): We reduce the operator's payable earnings by €2.(LIAbility means should beon right but we put left becasuse it needs to reduce,
         *  on the ohter hand, we do increase a special temp accopunt which will potential be in our commission in future)
         * * - CR Platform Commission Escrow (+Liability): We lock that €2 into an escrow safety cage.
         * (Still a liability because chargebacks could force us to return it) THAT MAKES IT NOT AN ASSET OR REVENUE YET,BECAUSE WE MIGHT HAVE TO PAY BACK IF 14 DAYS ARE NOT PASSED AFTER AUTH
         */
        val amount = Amount.of(200, eur)

        val entries = JournalEntry.commissionFeeRegistered(5L, paymentId, journalId, amount, commissionEscrowAccount, operatorCommissionAccount)
        val entry = entries.first()

        assertBalanced(entry)
        assertEquals(JournalType.COMMISSION_FEE, entry.journalType)
        assertPostingContains(entry, operatorCommissionAccount, isDebit = true, expectedAmount = 200)
        assertPostingContains(entry, commissionEscrowAccount, isDebit = false, expectedAmount = 200)
    }

    @Test
    fun `recognizePlatformRevenue - move money in escrow account into  confirmed earnings, so it was a liability,now a revenue`() {
        /*
         * SCENARIO: 14 days have passed. The risk window is closed. Mor-DC officially claims the €2 fee as profit.
         * * RULE:
         * - DR Platform Commission Escrow (-Liability,This account is a de): Remove the funds from the safety cage.
         * - CR Platform Operational Revenue (+Revenue): Officially record the €2 as company profit.
         *

         *Does looking at it as "Deferred Revenue" make the Liability classification click
         */
        val amount = Amount.of(200, eur)

        val entries = JournalEntry.recognizePlatformRevenue(6L, journalId, amount, commissionEscrowAccount, platformOperationalRevenueAccount)
        val entry = entries.first()

        assertBalanced(entry)
        assertEquals(JournalType.REVENUE_RECOGNITION, entry.journalType)
        assertPostingContains(entry, commissionEscrowAccount, isDebit = true, expectedAmount = 200)
        assertPostingContains(entry, platformOperationalRevenueAccount, isDebit = false, expectedAmount = 200)
    }

    @Test
    fun `payout - disburses physical cash to a vendor's external bank account`() {
        /*
         * SCENARIO: A sub-seller requests a wire transfer for their €98 earnings.
         * * RULE:
         * - DR Seller Balance (-Liability): We no longer owe the seller this money.reduce liability(credit),so seller balance on the left(DR) , PlatformCAsh(Debit) is reduced (so put in right side)
         * - CR Platform Cash (-Asset): Our physical bank account liquidity decreases.
         */
        val amount = Amount.of(9_800, eur)

        val entries = JournalEntry.payout(7L, paymentId, txId, journalId, amount, subSellerAccount, platformCashAccount)
        val entry = entries.first()

        assertBalanced(entry)
        assertEquals(JournalType.PAYOUT, entry.journalType)
        assertPostingContains(entry, subSellerAccount, isDebit = true, expectedAmount = 9_800)
        assertPostingContains(entry, platformCashAccount, isDebit = false, expectedAmount = 9_800)
    }

    @Test
    fun `refund - reverses captured positions for a customer return`() {
        /*
         * SCENARIO: A customer is refunded €100.
         * * RULE:
         * - DR Merchant Suspense / Pool (-Liability): Deduct the €100 from the merchant's balance.
         * - CR PSP Receivable (-Asset): Record that Adyen will pull this €100 from our next settlement.
         * - DR Auth Liability (+Liability) & CR Auth Receivable (+Asset): Temporarily reopen the auth states for tracking.
         */
        val amount = Amount.of(10_000, eur)

        val entries = JournalEntry.refund(8L, paymentId, txId, journalId, amount, authReceivableAccount, authLiabilityAccount, merchantSuspenseAccount, pspReceivableAccount)
        val entry = entries.first()

        assertBalanced(entry)
        assertEquals(JournalType.REFUND, entry.journalType)
        assertPostingContains(entry, merchantSuspenseAccount, isDebit = true, expectedAmount = 10_000)
        assertPostingContains(entry, pspReceivableAccount, isDebit = false, expectedAmount = 10_000)
        assertPostingContains(entry, authLiabilityAccount, isDebit = true, expectedAmount = 10_000)
        assertPostingContains(entry, authReceivableAccount, isDebit = false, expectedAmount = 10_000)
    }
}