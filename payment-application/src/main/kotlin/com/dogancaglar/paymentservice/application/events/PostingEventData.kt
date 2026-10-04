package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType

/**
 * Serializable representation of a posting for event publication.
 *
 * This class is only created through the factory method to ensure invariants are maintained.
 */
data class PostingEventData private constructor(
    val accountCode: String, // e.g., "MERCHANT_PAYABLE.MERCHANT-456"
    val accountType: LedgerAccountType,
    val amount: Long, // quantity in minor currency units
    val currency: String, // e.g., "USD"
    val direction: PostingDirection // DEBIT or CREDIT
) {
    companion object {
        /**
         * Factory method to create PostingEventData.
         *
         * @param accountCode Account code (e.g., "MERCHANT_PAYABLE.MERCHANT-456")
         * @param accountType Account type
         * @param amount Amount in minor currency units
         * @param currency Currency code (e.g., "USD")
         * @param direction Posting direction (DEBIT or CREDIT)
         * @return PostingEventData instance
         */
        fun create(
            accountCode: String,
            accountType: LedgerAccountType,
            amount: Long,
            currency: String,
            direction: PostingDirection
        ): PostingEventData {
            return PostingEventData(
                accountCode = accountCode,
                accountType = accountType,
                amount = amount,
                currency = currency,
                direction = direction
            )
        }
    }
}

/**
 * Direction of a posting entry.
 */
enum class PostingDirection {
    DEBIT,
    CREDIT
}
