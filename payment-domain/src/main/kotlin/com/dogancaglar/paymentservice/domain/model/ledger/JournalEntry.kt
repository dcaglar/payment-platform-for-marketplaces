package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.exception.LedgerDomainException
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.require
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
    val globalJournalEntryId: Long,
    val journalType: JournalType,
    val name: String,
    val paymentId: PaymentId, // 🛡️ Strict Domain Primitive
    val txId: TxId?, // 🛡️  Light Domain Primitive
    val postings: List<Posting>,
    val reason: String? = ""
) {

    init {
        require(postings.size >= 2) {
            LedgerDomainException.LessThanTwoPostingsInJournalException(
                "JournalEntry must have at least 2 postings, but had ${postings.size}"
            )
        }
        val totalDebit = postings.filterIsInstance<Posting.Debit>().sumOf { it.amount.quantity }
        val totalCredit = postings.filterIsInstance<Posting.Credit>().sumOf { it.amount.quantity }
        require(totalDebit == totalCredit) {
            LedgerDomainException.UnbalancedJournalEntryException(
                "Unbalanced JournalEntry [$id]: debits=$totalDebit, credits=$totalCredit"
            )
        }
        require(globalJournalEntryId > 0) {
            LedgerDomainException.InvariantViolationException(
                "globalJournalEntryId must be positive, was $globalJournalEntryId"
            )
        }
        val duplicates = postings
            .groupingBy { it.account.accountCode }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicates.isEmpty()) {
            LedgerDomainException.DuplicateAccountInJournalException(
                "JournalEntry [$id] contains duplicate accounts: ${duplicates.joinToString(", ")}"
            )
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
            globalJournalEntryId: Long,
            authTx: Tx.AuthorizationTx, // the payment, the tx and the authorized amount
            journalIdentifier: String,
            authReceivable: LedgerAccount,
            authLiability: LedgerAccount,
            reason: String? = "Auth Hold"
        ): List<JournalEntry> = listOf(
            JournalEntry(
                id = "AUTH:$journalIdentifier",
                globalJournalEntryId = globalJournalEntryId,
                journalType = JournalType.AUTHORIZATION,
                name = "AuthorizationTx Hold",
                paymentId = authTx.paymentId,
                txId = authTx.txId,
                postings = listOf(
                    Posting.Debit.create(authReceivable, authTx.amount),
                    Posting.Credit.create(authLiability, authTx.amount)
                ),
                reason = reason
            )
        )

        // =====================================================================
        // CAPTURE — This journal entry is recorded when external PSP notifies Mor-DC platform regarding the final
        // status of capture, money is still not in Mor-DC account, but PSP confrms that it will send within 3 or 5
        // days
        // =====================================================================

        fun captureGrossAsset(
            globalJournalEntryId: Long,
            captureTx: Tx.CaptureTx, // the payment and the tx
            journalIdentifier: String,
            capturedAmount: Amount, // what the PSP confirmed
            authReceivable: LedgerAccount,
            authLiability: LedgerAccount,
            merchantGrossPool: LedgerAccount,
            pspReceivable: LedgerAccount,
            reason: String? = "CaptureConfirmation"
        ): List<JournalEntry> = listOf(
            JournalEntry(
                id = "CAPTURE:$journalIdentifier",
                globalJournalEntryId = globalJournalEntryId,
                journalType = JournalType.CAPTURE,
                name = "Gross Asset Capture Pool on ${merchantGrossPool.accountCode}",
                paymentId = captureTx.paymentId,
                txId = captureTx.txId,
                postings = listOf(
                    Posting.Debit.create(authLiability, capturedAmount),
                    Posting.Credit.create(authReceivable, capturedAmount),
                    Posting.Debit.create(pspReceivable, capturedAmount),
                    Posting.Credit.create(merchantGrossPool, capturedAmount)
                ),
                reason = reason
            )
        )

        fun internalTransfer(
            globalJournalEntryId: Long,
            paymentId: PaymentId,
            journalIdentifier: String,
            amount: Amount,
            sourceAccount: LedgerAccount,
            targetAccount: LedgerAccount,
            // 🎯 e.g., "MARKETPLACE_SELLER_SPLIT" or "PLATFORM_COMMISSION_FEE"
            allocationReason: String? = "INTERNAL_TRANSFER"
        ): List<JournalEntry> = listOf(
            JournalEntry(
                id = "INTERNAL_TRANSFER:$journalIdentifier",
                globalJournalEntryId = globalJournalEntryId,
                journalType = JournalType.INTERNAL_TRANSFER,
                name = "Internal Transfer between virtual accounts",
                paymentId = paymentId,
                txId = null, // 🔴 Cleanly null. No external proof required.
                postings = listOf(
                    Posting.Debit.create(sourceAccount, amount),
                    Posting.Credit.create(targetAccount, amount)
                ),
                reason = allocationReason, // 🟢 Directly states the business purpose
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
         *   DR CAPTURE_SUSPENSE    (reduces the merchant's balance)
         *   CR PSP_RECEIVABLE      (records the outbound refund to PSP)
         *   DR AUTH_LIABILITY      (re-opens the liability position)
         *   CR AUTH_RECEIVABLE     (reduces the receivable by refund amount)
         *
         * Called by: PspResultConsumer, processing a PaymentRefunded webhook event.
         */
        fun refund(
            globalJournalEntryId: Long,
            refundTx: Tx.RefundTx, // the payment, the tx and the refunded amount
            journalIdentifier: String,
            authReceivable: LedgerAccount,
            authLiability: LedgerAccount,
            merchantGrossPool: LedgerAccount,
            pspReceivable: LedgerAccount,
            reason: String? = "REFUNDCONFIRMATION"
        ): List<JournalEntry> = listOf(
            JournalEntry(
                id = "REFUND:$journalIdentifier",
                globalJournalEntryId = globalJournalEntryId,
                journalType = JournalType.REFUND,
                name = "Payment Refund",
                paymentId = refundTx.paymentId,
                txId = refundTx.txId,
                postings = listOf(
                    Posting.Debit.create(merchantGrossPool, refundTx.amount),
                    Posting.Credit.create(pspReceivable, refundTx.amount),
                    Posting.Debit.create(authLiability, refundTx.amount),
                    Posting.Credit.create(authReceivable, refundTx.amount)
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
            settleTx: Tx.SettleTx, // the payment, the tx and the gross amount the PSP settled (e.g., €3,000)
            journalIdentifier: String, // e.g., "ADYEN_SDR_LINE_998124"
            netCashAmount: Amount, // Cash deposited after fees (e.g., €2,940)
            pspFeeAmount: Amount, // Exact fees taken out (e.g., €60)
            platformCash: LedgerAccount, // PLATFORM_CASH.GLOBAL.EUR
            pspReceivable: LedgerAccount, // PSP_RECEIVABLE.GLOBAL.EUR
            pspFeeExpense: LedgerAccount, // {PSP_FEE_EXPENSE}.GLOBAL.EUR
            reason: String? = "CaptureClearedMoneyisInOurBank"
        ): List<JournalEntry> {
            return listOf(
                JournalEntry(
                    id = "SETTLE:$journalIdentifier",
                    globalJournalEntryId = globalJournalEntryId,
                    journalType = JournalType.SETTLEMENT,
                    name = "Acquirer Network Line-Item Reconciled Settlement",
                    paymentId = settleTx.paymentId, // 🟢 No longer a blind sentinel! Absolute audit trail.
                    txId = settleTx.txId,
                    postings = listOf(
                        Posting.Debit.create(platformCash, netCashAmount), // Physical vault asset increase 🟢
                        Posting.Debit.create(pspFeeExpense, pspFeeAmount), // Direct operational fee expense 🟢
                        Posting.Credit.create(
                            pspReceivable,
                            settleTx.grossAmount
                        ) // Reconciles outstanding gateway IOU to zero 🔴
                    ),
                    reason = reason
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
         *        ├──► Path A: Direct Sale -> Moves 100% to MERCHANT_DIRECT_PAYABLE
         *        │                            │
         *        │                            ▼ (In same DB transaction)
         *        │                            ⚡ Run commissionFeeRegistered()
         *        │
         *        └──► Path B: Marketplace -> Moves splits to SELLER_PAYABLE / MERCHANT_COMMISSION_PAYABLE
         *                                     │
         *                                     ▼ (In same DB transaction)
         *                                     ⚡ Run commissionFeeRegistered()
         * commissionFeeRegistered
         *
         * Carves out Mor-DC's infrastructure fee from the merchant's payable account
         * and holds it in a merchant-specific fee reserve account to manage chargeback risk.
         *
         * @param feeReserveAccount Must be LedgerAccountType.PLATFORM_FEE_RESERVE for the tenant
         * @param merchantPayableAccount MERCHANT_DIRECT_PAYABLE (direct sale) or MERCHANT_COMMISSION_PAYABLE
         * (marketplace)
         */
        fun commissionFeeRegistered(
            globalJournalEntryId: Long,
            paymentId: PaymentId,
            journalIdentifier: String,
            commissionFee: Amount,
            feeReserveAccount: LedgerAccount, // ◄ PLATFORM_FEE_RESERVE.MARKETPLACE-1.EUR
            merchantPayableAccount: LedgerAccount, // ◄ MERCHANT_COMMISSION_PAYABLE.MARKETPLACE-1.EUR
            reason: String? = "CommFeeRegistered"
        ): List<JournalEntry> {
            require(feeReserveAccount.type == LedgerAccountType.PLATFORM_FEE_RESERVE) {
                LedgerDomainException.InvariantViolationException(
                    "Target must be a tenant fee reserve account: ${feeReserveAccount.accountCode}"
                )
            }

            return listOf(
                JournalEntry(
                    id = "MOR_DC_COMMISSION:$journalIdentifier",
                    globalJournalEntryId = globalJournalEntryId,
                    journalType = JournalType.COMMISSION_FEE,
                    name = "Mor-DC Platform Fee Reserve Charge — Tenant Isolated",
                    paymentId = paymentId,
                    txId = null,
                    postings = listOf(
                        Posting.Debit.create(
                            merchantPayableAccount,
                            commissionFee
                        ), // Reduces what we owe the merchant 🔴
                        Posting.Credit.create(
                            feeReserveAccount,
                            commissionFee
                        ) // Increases the merchant's specific fee reserve 🟢
                    ),
                    reason = reason
                )
            )
        }

// =====================================================================
// 8. REVENUE RECOGNITION (Clearing Tenant Fee Reserve to Corporate Profit)
// =====================================================================
        /**
         * recognizePlatformRevenue
         *
         * Sweeps a specific tenant's matured fee reserve account after the refund safety window clears,
         * moving those funds into Mor-DC's global platform revenue.
         *
         * @param feeReserveAccount Must be LedgerAccountType.PLATFORM_FEE_RESERVE for the target tenant
         * @param platformRevenue Must be LedgerAccountType.PLATFORM_REVENUE for GLOBAL
         */
        fun recognizePlatformRevenue(
            globalJournalEntryId: Long,
            recognitionIdentifier: String,
            maturedFeeAmount: Amount,
            feeReserveAccount: LedgerAccount, // ◄ PLATFORM_FEE_RESERVE.MARKETPLACE-1.EUR
            platformRevenue: LedgerAccount, // ◄ PLATFORM_REVENUE.GLOBAL.EUR
            reason: String? = "MovedFromFeeReserveToMorRevenue"

        ): List<JournalEntry> {
            require(feeReserveAccount.type == LedgerAccountType.PLATFORM_FEE_RESERVE) {
                LedgerDomainException.InvariantViolationException(
                    "Source must be a tenant fee reserve account: ${feeReserveAccount.accountCode}"
                )
            }
            require(platformRevenue.type == LedgerAccountType.PLATFORM_REVENUE) {
                LedgerDomainException.InvariantViolationException(
                    "Destination must be global platform revenue: ${platformRevenue.accountCode}"
                )
            }

            return listOf(
                JournalEntry(
                    id = "REV_REC:$recognitionIdentifier",
                    globalJournalEntryId = globalJournalEntryId,
                    journalType = JournalType.REVENUE_RECOGNITION,
                    name = "Platform Fee Release — Tenant: ${feeReserveAccount.accountCode}",
                    paymentId = PaymentId(0L), // System batch level
                    txId = null,
                    postings = listOf(
                        Posting.Debit.create(
                            feeReserveAccount,
                            maturedFeeAmount
                        ), // Frees the specific tenant hold 🔴
                        Posting.Credit.create(
                            platformRevenue,
                            maturedFeeAmount
                        ) // Adds to Mor-DC's aggregate profit 🟢
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
            payoutTx: Tx.PayoutTx, // the payment, the tx and the paid-out amount
            journalIdentifier: String,
            sourceBalanceAccount: LedgerAccount,
            platformCash: LedgerAccount,
            reason: String? = "PayOutToMerchant"
        ): List<JournalEntry> = listOf(
            JournalEntry(
                id = "PAYOUT:$journalIdentifier",
                globalJournalEntryId = globalJournalEntryId,
                journalType = JournalType.PAYOUT,
                name = "Outbound Wire Transfer to Merchant/Seller External Bank",
                paymentId = payoutTx.paymentId,
                txId = payoutTx.txId,
                postings = listOf(
                    Posting.Debit.create(sourceBalanceAccount, payoutTx.amount),
                    Posting.Credit.create(platformCash, payoutTx.amount)
                ),
                reason = reason, // 🟢 Directly states the business purpose
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
            id = id,
            globalJournalEntryId = globalJournalEntryId,
            journalType = txType,
            name = name,
            paymentId = paymentId,
            txId = txId,
            postings = postings,
            reason = reason
        )
    }
}
