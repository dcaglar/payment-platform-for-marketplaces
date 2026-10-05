package com.dogancaglar.paymentservice.domain.model.ledger

enum class NormalBalance {
    DEBIT,
    CREDIT
}

enum class AccountCategory {
    ASSET, // dr
    EXPENSE, // dr
    LIABILITY, // cr
    EQUITY, // cr
    REVENUE // cr
}

/**
 * Who a ledger account belongs to. Decides which ledger accounts are generated for whom:
 * the platform once per currency, every merchant, and every seller.
 */
enum class AccountOwner {
    PLATFORM,
    MERCHANT,
    SELLER
}

/**
 * The closed set of ledger account types. Each type fixes its normal balance, its category and its
 * owner level; no ledger account can exist with any other combination.
 */
enum class LedgerAccountType(
    val normalBalance: NormalBalance,
    val category: AccountCategory,
    val owner: AccountOwner
) {
    // === ASSETS ===
    PLATFORM_CASH(
        NormalBalance.DEBIT,
        AccountCategory.ASSET,
        AccountOwner.PLATFORM
    ), // the real bank account the PSP pays into
    PSP_RECEIVABLE(
        NormalBalance.DEBIT,
        AccountCategory.ASSET,
        AccountOwner.PLATFORM
    ), // money the PSP owes us after a confirmed capture

    /** Memo: authorized but not yet captured, per merchant. Released by the capture. */
    AUTH_RECEIVABLE(NormalBalance.DEBIT, AccountCategory.ASSET, AccountOwner.MERCHANT),

    // === LIABILITIES ===
    /** Memo counterpart of AUTH_RECEIVABLE, per merchant. */
    AUTH_LIABILITY(NormalBalance.CREDIT, AccountCategory.LIABILITY, AccountOwner.MERCHANT),

    /**
     * 1. The Raw Ingestion Landing Lane (Transient Clearing Pad)
     * Tracks the initial, unassigned gross captured volume received from the PSP webhook on Day 1.
     * This acts strictly as a temporary suspense pool and must ALWAYS be drained down to exactly €0
     * by downstream async internal transfer commands (either via multi-party seller splits or a 100% direct revenue
     * reclassification).
     */
    CAPTURE_SUSPENSE(NormalBalance.CREDIT, AccountCategory.LIABILITY, AccountOwner.MERCHANT),

    /**
     * 2. The Finalized Operator Direct Sales Folder (Direct Revenue)
     * Holds the accumulated, finalized payable funds from direct e-commerce sales where the marketplace operator
     * sold items from their own inventory (no third-party splits involved).
     * Exactly one account of this type exists per unique marketplace merchant master_account_id.
     */
    MERCHANT_DIRECT_PAYABLE(NormalBalance.CREDIT, AccountCategory.LIABILITY, AccountOwner.MERCHANT),

    /**
     * 3. The Finalized Operator Commission Earnings Folder (Platform Revenue Share)
     * Tracks the accumulated, finalized balance of earned platform usage or processing commissions charged
     * by the marketplace operator to their third-party sub-sellers via split arrays.
     * Exactly one account of this type exists per unique marketplace merchant master_account_id.
     */
    MERCHANT_COMMISSION_PAYABLE(NormalBalance.CREDIT, AccountCategory.LIABILITY, AccountOwner.MERCHANT),

    /**
     * 4. The Finalized Sub-Seller Revenue Folder (Vendor Balance)
     * Tracks the finalized, net revenue payable balance belonging to a specific third-party sub-seller
     * onboarded under the marketplace operator's master ecosystem.
     * Multiple accounts of this type can exist under the same master_account_id, isolated by their unique sub-seller
     * entity IDs.
     */
    SELLER_PAYABLE(NormalBalance.CREDIT, AccountCategory.LIABILITY, AccountOwner.SELLER),
    PLATFORM_FEE_RESERVE(
        NormalBalance.CREDIT,
        AccountCategory.LIABILITY,
        AccountOwner.MERCHANT
    ), // the fee we (mor-dc) charge our marketplace merchant, held in reserve until the refund window passes

    // === REVENUE ===
    PLATFORM_REVENUE(
        NormalBalance.CREDIT,
        AccountCategory.REVENUE,
        AccountOwner.PLATFORM
    ), // guaranteed earnings, unlike PLATFORM_FEE_RESERVE

    // === EXPENSE ===
    PSP_FEE_EXPENSE(
        NormalBalance.DEBIT,
        AccountCategory.EXPENSE,
        AccountOwner.PLATFORM
    ) // the PSP fee we (Mor-DC) pay to the external PSP
}
