package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId

/**
 * JournalEntry
 *
 * Represents one atomic, balanced double-entry accounting event.
 * Every JournalEntry is immutable and validates itself at construction time.
 *
 * Invariants enforced at construction:
 *  - Must have at least 2 postings (one debit, one credit minimum).
 *  - Total debit quantity must equal total credit quantity (balanced entry).
 *  - No duplicate account codes within a single entry.
 *
 * All instances must be created via the factory methods in [JournalFactory].
 * Direct construction is prohibited (private constructor).
 *
 * ============================================================
 */
class JournalEntry private constructor(
    val id: String,
    val globalJournalEntryId:Long,
    val journalType: JournalType,
    val name: String,
    val paymentId: PaymentId, // 🛡️ Strict Domain Primitive
    val txId: TxId?,           // 🛡️  Light Domain Primitive
    val postings: List<Posting>,
    val reason: String?=""
) {

    init {
        require(postings.size >= 2) {
            "JournalEntry must have at least 2 postings, but had ${postings.size}"
        }
        val totalDebit  = postings.filterIsInstance<Posting.Debit>().sumOf { it.amount.quantity }
        val totalCredit = postings.filterIsInstance<Posting.Credit>().sumOf { it.amount.quantity }
        require(totalDebit == totalCredit) {
            "Unbalanced JournalEntry [$id]: debits=$totalDebit, credits=$totalCredit"
        }
        require(globalJournalEntryId >0)
        val duplicates = postings
            .groupingBy { it.account.accountCode }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicates.isEmpty()) {
            "JournalEntry [$id] contains duplicate accounts: ${duplicates.joinToString(", ")}"
        }
    }

    override fun toString(): String =
        "JournalEntry(id='$id', txType=$journalType, name='$name', " +
        "postings=$postings)"

    companion object JournalFactory {

        // =====================================================================
        // AUTHORIZATION  is succesful in sync api call
        // =====================================================================

        fun authHold(
            globalJournalEntryId : Long,
            paymentId: PaymentId,
            txId: TxId, // <-- Added! (This is the ID of the AuthorizationTx)
            journalIdentifier: String,
            authorizedAmount: Amount,
            authReceivable: Account,
            authLiability: Account,
            reason : String?="Auth Hold"
        ): List<JournalEntry> = listOf(
            JournalEntry(
                id        = "AUTH:$journalIdentifier",
                globalJournalEntryId = globalJournalEntryId,
                journalType    = JournalType.AUTHORIZATION,
                name      = "AuthorizationTx Hold",
                paymentId = paymentId,
                txId      = txId,
                postings  = listOf(
                    Posting.Debit.create(authReceivable, authorizedAmount),
                    Posting.Credit.create(authLiability, authorizedAmount)
                ),
                reason = reason
            )
        )




        // =====================================================================
        // CAPTURE — This journal entry is recorded when external PSP notifies Mor-DC platform regarding the final status of capture, money is still not in Mor-DC account, but PSP confrms that it will send within 3 or 5 days
        // =====================================================================

        fun captureGrossAsset(
            globalJournalEntryId: Long,
            paymentId: PaymentId,
            txId: TxId,
            journalIdentifier: String,
            capturedAmount: Amount,
            authReceivable: Account,
            authLiability: Account,
            merchantGrossPool: Account,
            pspReceivable: Account,
            reason : String?="CaptureConfirmation"
        ): List<JournalEntry> = listOf(
            JournalEntry(
                id             = "CAPTURE:$journalIdentifier",
                globalJournalEntryId = globalJournalEntryId,
                journalType    = JournalType.CAPTURE,
                name           = "Gross Asset Capture Pool on ${merchantGrossPool.accountCode}",
                paymentId      = paymentId,
                txId           = txId,
                postings       = listOf(
                    Posting.Debit.create(authLiability, capturedAmount),
                    Posting.Credit.create(authReceivable, capturedAmount),
                    Posting.Debit.create(pspReceivable, capturedAmount),
                    Posting.Credit.create(merchantGrossPool, capturedAmount)
                ),
                reason =reason
            )
        )




        fun internalTransfer(
            globalJournalEntryId: Long,
            paymentId: PaymentId,
            journalIdentifier: String,
            amount: Amount,
            sourceAccount: Account,
            targetAccount: Account,
            allocationReason: String?="INTERNAL_TRANSFER" // 🎯 e.g., "MARKETPLACE_SELLER_SPLIT" or "PLATFORM_COMMISSION_FEE"
        ): List<JournalEntry> = listOf(
            JournalEntry(
                id          = "INTERNAL_TRANSFER:${journalIdentifier}",
                globalJournalEntryId = globalJournalEntryId,
                journalType = JournalType.INTERNAL_TRANSFER,
                name        = "Internal Transfer between virtual accounts",
                paymentId   = paymentId,
                txId        = null,             // 🔴 Cleanly null. No external proof required.
                postings    = listOf(
                    Posting.Debit.create(sourceAccount, amount),
                    Posting.Credit.create(targetAccount, amount)
                ),
                reason      = allocationReason, // 🟢 Directly states the business purpose
            )
        )

        // =====================================================================
        // REFUND
        // =====================================================================

        /**
         * refund
         *
         * Reverses a prior capture by crediting the PSP with the refunded amount
         * and debiting the merchant's gross pool.
         *
         * Postings:
         *   DR MERCHANT_GROSS_POOL (reduces the merchant's balance)
         *   CR PSP_RECEIVABLE      (records the outbound refund to PSP)
         *   DR AUTH_LIABILITY      (re-opens the liability position)
         *   CR AUTH_RECEIVABLE     (reduces the receivable by refund amount)
         *
         * Called by: PspResultConsumer, processing a PaymentRefunded webhook event.
         */
        fun refund(
            globalJournalEntryId: Long,
            paymentId: PaymentId,
            txId: TxId,
            journalIdentifier: String,
            refundedAmount: Amount,
            authReceivable: Account,
            authLiability: Account,
            merchantGrossPool: Account,
            pspReceivable: Account,
            reason : String?="REFUNDCONFIRMATION"
        ): List<JournalEntry> = listOf(
            JournalEntry(
                id             = "REFUND:$journalIdentifier",
                globalJournalEntryId = globalJournalEntryId,
                journalType    = JournalType.REFUND,
                name           = "Payment Refund",
                paymentId      = paymentId,
                txId           = txId,
                postings       = listOf(
                    Posting.Debit.create(merchantGrossPool, refundedAmount),
                    Posting.Credit.create(pspReceivable, refundedAmount),
                    Posting.Debit.create(authLiability, refundedAmount),
                    Posting.Credit.create(authReceivable, refundedAmount)
                ),
                reason = reason
            )
        )


// =====================================================================
// 5. SETTLEMENT (Processed Line-by-Line From Psp's Settlement Report)
// =====================================================================
        /**
         * settlementLineItem
         *
         * Records the official clearing of an individual capture transaction.
         * Converts the abstract gateway receivable into physical platform cash liquidity,
         * while isolating the exact processing expense levied by the network for this payment.
         */
        fun settlementLineItem(
            globalJournalEntryId: Long,
            paymentId: PaymentId,             // 🎯 The true, original Payment identifier
            settlementTxId: TxId,            // The fresh unique ID for this settlement event
            journalIdentifier: String,       // e.g., "ADYEN_SDR_LINE_998124"
            grossAmount: Amount,             // Original capture volume (e.g., €3,000)
            netCashAmount: Amount,           // Cash deposited after fees (e.g., €2,940)
            pspFeeAmount: Amount,            // Exact fees taken out (e.g., €60)
            platformCash: Account,           // PLATFORM_CASH.GLOBAL.EUR
            pspReceivable: Account,          // PSP_RECEIVABLES.GLOBAL.EUR
            pspFeeExpense: Account ,          // {PSP_FEE_EXPENSE}.GLOBAL.EUR
            reason : String?="CaptureClearedMoneyisInOurBank"
        ): List<JournalEntry> {

            return listOf(
                JournalEntry(
                    id             = "SETTLE:$journalIdentifier",
                    globalJournalEntryId = globalJournalEntryId,
                    journalType    = JournalType.SETTLEMENT,
                    name           = "Acquirer Network Line-Item Reconciled Settlement",
                    paymentId      = paymentId,   // 🟢 No longer a blind sentinel! Absolute audit trail.
                    txId           = settlementTxId,
                    postings       = listOf(
                        Posting.Debit.create(platformCash, netCashAmount),    // Physical vault asset increase 🟢
                        Posting.Debit.create(pspFeeExpense, pspFeeAmount),   // Direct operational fee expense 🟢
                        Posting.Credit.create(pspReceivable, grossAmount)    // Reconciles outstanding gateway IOU to zero 🔴
                    ),
                    reason =reason
                )
            )
        }

        // =====================================================================
// 6. MOR-DC PLATFORM COMMISSION REGISTERED (Isolated Per Tenant)
// =====================================================================
        /**
         * [Adyen Capture Webhook]
         *        │
         *        ▼
         * 1. PspResultConsumer writes CAPTURE entry
         *        │
         *        ▼ (Publishes Kafka Event)
         * 2. GrossCaptureAllocationConsumer wakes up
         *        │
         *        ├──► Path A: Direct Sale -> Moves 100% to DIRECT_REVENUE
         *        │                            │
         *        │                            ▼ (In same DB transaction)
         *        │                            ⚡ Run commissionFeeRegistered()
         *        │
         *        └──► Path B: Marketplace -> Moves splits to BALANCE_ACCOUNTs
         *                                     │
         *                                     ▼ (In same DB transaction)
         *                                     ⚡ Run commissionFeeRegistered()
         * commissionFeeRegistered
         *
         * Carves out Mor-DC's infrastructure fee from the merchant's gross pool
         * and locks it into a merchant-specific escrow container to manage chargeback risk.
         *
         * @param commissionEscrowAccount Must be AccountType.PLATFORM_COMMISSION_ESCROW for the tenant
         * @param merchantGrossPool Must be AccountType.MERCHANT_GROSS_CAPTURE_SUSPENSE or MARKETPLACE_COMMISSION_REVENUE_BALANCE_ACCOUNT
         */
        fun commissionFeeRegistered(
            globalJournalEntryId: Long,
            paymentId: PaymentId,
            journalIdentifier: String,
            commissionFee: Amount,
            commissionEscrowAccount: Account, // ◄ PLATFORM_COMMISSION_ESCROW.MARKETPLACE-1.EUR
            merchantGrossPool: Account ,      // ◄ MARKETPLACE_COMMISSION_REVENUE_BALANCE_ACCOUNT.MARKETPLACE-1.EUR
            reason : String?="CommFeeRegistered"
        ): List<JournalEntry> {
            require(commissionEscrowAccount.type == AccountType.PLATFORM_COMMISSION_ESCROW) {
                "Target must be a tenant escrow account: ${commissionEscrowAccount.accountCode}"
            }


            return listOf(
                JournalEntry(
                    id             = "MOR_DC_COMMISSION:$journalIdentifier",
                    globalJournalEntryId = globalJournalEntryId,
                    journalType    = JournalType.COMMISSION_FEE,
                    name           = "Mor-DC Platform Escrow Fee Charge — Tenant Isolated",
                    paymentId      = paymentId,
                    txId           = null,
                    postings       = listOf(
                        Posting.Debit.create(merchantGrossPool, commissionFee),       // Reduces the merchant's folder 🔴
                        Posting.Credit.create(commissionEscrowAccount, commissionFee)   // Increases the merchant's specific escrow lock 🟢
                    ),
                    reason = reason
                )
            )
        }

// =====================================================================
// 8. REVENUE RECOGNITION (Clearing Tenant Escrow to Corporate Profit)
// =====================================================================
        /**
         * recognizePlatformRevenue
         *
         * Sweeps a specific tenant's matured escrow account after the refund safety window clears,
         * moving those funds into Mor-DC's global corporate operational revenue.
         *
         * @param commissionEscrowAccount Must be AccountType.PLATFORM_COMMISSION_ESCROW for the target tenant
         * @param platformOperationalRevenue Must be AccountType.PLATFORM_OPERATIONAL_REVENUE for GLOBAL
         */
        fun recognizePlatformRevenue(
            globalJournalEntryId: Long,
            recognitionIdentifier: String,
            maturedFeeAmount: Amount,
            commissionEscrowAccount: Account,   // ◄ PLATFORM_COMMISSION_ESCROW.MARKETPLACE-1.EUR
            platformOperationalRevenue: Account ,// ◄ PLATFORM_OPERATIONAL_REVENUE.GLOBAL.EUR
            reason : String?="MovedFromEscrowToMorRevenue"

        ): List<JournalEntry> {
            require(commissionEscrowAccount.type == AccountType.PLATFORM_COMMISSION_ESCROW) {
                "Source must be a tenant escrow account: ${commissionEscrowAccount.accountCode}"
            }
            require(platformOperationalRevenue.type == AccountType.PLATFORM_OPERATIONAL_REVENUE) {
                "Destination must be global operational revenue: ${platformOperationalRevenue.accountCode}"
            }

            return listOf(
                JournalEntry(
                    id             = "REV_REC:$recognitionIdentifier",
                    globalJournalEntryId = globalJournalEntryId,
                    journalType    = JournalType.REVENUE_RECOGNITION,
                    name           = "Platform Fee Release — Tenant: ${commissionEscrowAccount.accountCode}",
                    paymentId      = PaymentId(0L), // System batch level
                    txId           = null,
                    postings       = listOf(
                        Posting.Debit.create(commissionEscrowAccount, maturedFeeAmount),   // Frees the specific tenant hold 🔴
                        Posting.Credit.create(platformOperationalRevenue, maturedFeeAmount) // Adds to Mor-DC's aggregate profit 🟢
                    ),
                    reason
                )
            )
        }


        // =====================================================================
        // PAYOUT
        // =====================================================================

        // =====================================================================
        // 7. PAYOUT (Clearing Liabilities & Pushing Cash to External Bank Accounts)
        // =====================================================================
        fun payout(
            globalJournalEntryId: Long,
            paymentId: PaymentId,
            txId: TxId,
            journalIdentifier: String,
            payoutAmount: Amount,
            sourceBalanceAccount: Account,
            platformCash: Account,
            reason : String?="PayOutToMerchant"
        ): List<JournalEntry> = listOf(
            JournalEntry(
                id             = "PAYOUT:$journalIdentifier",
                globalJournalEntryId = globalJournalEntryId,
                journalType    = JournalType.PAYOUT,
                name           = "Outbound Wire Transfer to Merchant/Seller External Bank",
                paymentId      = paymentId,
                txId           = txId,
                postings       = listOf(
                    Posting.Debit.create(sourceBalanceAccount, payoutAmount),
                    Posting.Credit.create(platformCash, payoutAmount)
                ),
                reason      = reason, // 🟢 Directly states the business purpose
            )
        )

        // =====================================================================
        // PERSISTENCE REHYDRATION
        // =====================================================================

        /**
         * fromPersistence
         *
         * Rehydrates a JournalEntry from persisted database rows.
         * Used exclusively by the repository layer. No business validation runs here
         * beyond the init{} block invariants — assume the DB holds valid, balanced data.
         */
        fun rehytrate(
            id: String,
            globalJournalEntryId: Long,
            txType: JournalType,
            name: String,
            paymentId: PaymentId,
            txId: TxId?,
            postings: List<Posting>,
            reason: String?
        ): JournalEntry = JournalEntry(
            id            = id,
            globalJournalEntryId = globalJournalEntryId,
            journalType        = txType,
            name          = name,
            paymentId     = paymentId,
            txId          = txId,
            postings      = postings,
            reason = reason
        )
    }
}
