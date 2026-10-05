package com.dogancaglar.paymentservice.domain.exception

/** A rule of a payment intent was violated, or the intent is missing: non-retryable. */
sealed class PaymentIntentDomainException(message: String) : NonRetryableException(message) {

    /** No payment intent with this id for this merchant (another merchant's is "not found" too). */
    class PaymentIntentNotFoundException(message: String) : PaymentIntentDomainException(message)

    /** A required value is missing or wrong when the intent is created or rehydrated. */
    class InvariantViolationException(message: String) : PaymentIntentDomainException(message)

    /** The intent's state machine doesn't allow this status change. */
    class InvalidStateTransitionException(message: String) : PaymentIntentDomainException(message)

    /** The splits don't add up to the intent's amount, or a split is invalid. */
    class SplitValidationException(message: String) : PaymentIntentDomainException(message)

    class SplitCurrencyMismatchException(message: String) : PaymentIntentDomainException(message)
}
