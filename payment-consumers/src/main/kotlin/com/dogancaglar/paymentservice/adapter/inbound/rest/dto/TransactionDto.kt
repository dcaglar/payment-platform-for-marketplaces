package com.dogancaglar.port.out.web.dto

import java.time.Instant

/** An amount in the smallest currency unit (cents). */
data class AmountDto(
    val quantity: Long,
    val currency: String
)

/**
 * One transaction (an authorized payment) as the back office shows it. In a list, the detail-only fields
 * (buyerId, pspReference, splits) are null.
 */
data class TransactionDto(
    val paymentId: String,
    val paymentIntentId: String,
    val orderId: String,
    val merchantAccount: String,
    val totalAmount: AmountDto,
    val status: String,
    val authorizedAt: Instant,
    val capturedAt: Instant?,
    val settledAt: Instant?,
    val detailUrl: String,
    val card: CardDto? = null, // in the list and the detail; null if the PSP did not report the card
    val buyerId: String? = null,
    val pspReference: String? = null,
    val processingModel: String? = null,
    val splits: List<TransactionSplitDto>? = null
)

/** The card a payment was made with: brand (VISA, MASTERCARD, AMEX, OTHER) and last 4 digits, nothing more. */
data class CardDto(
    val brand: String,
    val last4: String
)

/** One split line: a seller's part (SELLER_PAYABLE) or the marketplace's commission (MERCHANT_COMMISSION_PAYABLE). */
data class TransactionSplitDto(
    val accountType: String,
    val account: String,
    val amount: AmountDto
)
