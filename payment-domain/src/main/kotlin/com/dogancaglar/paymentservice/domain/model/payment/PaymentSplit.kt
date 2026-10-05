package com.dogancaglar.paymentservice.domain.model.payment

import com.dogancaglar.paymentservice.domain.exception.PaymentDomainException
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.require
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType

/**
 * PaymentSplit
 *
 * An immutable value object that encodes a single fund-routing instruction
 * within a payment. A collection of these is locked into the Payment aggregate
 * at authorization time and read back during the capture webhook phase to
 * generate INTERNAL_TRANSFER ledger postings.
 *
 * Invariants enforced at construction time:
 *  - account must be non-blank (identifies the seller, sub-merchant,
 *    or platform entity that is the beneficiary of this split).
 *  - amount must be positive.
 *  - accountType must be one of the canonical LedgerAccountType values.
 *
 * No cart items, product lines, or order-level concepts exist here.
 * This is a pure fintech routing primitive.
 *
 * @param accountType  The internal ledger bucket that receives funds.
 * @param account     Identifier of the owning entity (seller ID, merchant ID, etc.).
 * @param amount             The monetary amount to route to this account.
 */
data class PaymentSplit(
    val accountType: LedgerAccountType,
    val account: String,
    val amount: Amount
) {
    init {
        require(account.isNotBlank()) {
            PaymentDomainException.SplitValidationException("PaymentSplit.account must not be blank")
        }
        require(amount.isPositive()) {
            PaymentDomainException.SplitValidationException(
                "PaymentSplit.amount must be positive, but was ${amount.quantity}"
            )
        }
    }

    companion object {
        /**
         * Factory method providing explicit, named-argument construction
         * and a validation gate for incoming split instructions.
         */
        fun of(
            accountType: LedgerAccountType,
            account: String,
            amount: Amount
        ): PaymentSplit = PaymentSplit(
            accountType = accountType,
            account = account,
            amount = amount
        )
    }
}
