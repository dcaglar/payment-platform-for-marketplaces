package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.paymentservice.application.dto.PaymentSplitDto

/**
 * Payment-level details carried only by the AUTHORIZATION booking's JournalEntriesRecorded, so read models
 * (the back office) learn who and what the payment is from the first ledger event. Not part of the postings.
 */
data class AuthorizationDetails(
    val buyerId: String,
    val orderId: String,
    val pspReference: String,
    val processingModel: String,
    val splits: List<PaymentSplitDto>,
    val cardBrand: String? = null, // brand + last 4 of the card (null if the PSP did not say)
    val cardLast4: String? = null
) {
    companion object {
        fun from(event: PaymentAuthorized) = AuthorizationDetails(
            buyerId = event.buyerId,
            orderId = event.orderId,
            pspReference = event.pspReference,
            processingModel = event.processingModel,
            splits = event.splits,
            cardBrand = event.cardBrand,
            cardLast4 = event.cardLast4
        )
    }
}
