package com.dogancaglar.paymentservice.domain.exception

sealed class PaymentDomainException(message: String) : NonRetryableException(message) {

    class InvariantViolationException(message: String) : PaymentDomainException(message)

    /** No payment for this payment intent, although an event refers to it. */
    class PaymentNotFoundException(message: String) : PaymentDomainException(message)

    /** The payment has no capture tx in the state this step needs (e.g. no PENDING capture to confirm). */
    class CaptureTxNotFoundException(message: String) : PaymentDomainException(message)

    class InvalidStateTransitionException(message: String) : PaymentDomainException(message)

    class CaptureLimitExceededException(message: String) : PaymentDomainException(message)

    class RefundLimitExceededException(message: String) : PaymentDomainException(message)

    class InvalidCaptureAmountException(message: String) : PaymentDomainException(message)
    class InvalidRefundAmountException(message: String) : PaymentDomainException(message)

    class CurrencyMismatchException(message: String) : PaymentDomainException(message)

    /** An amount must be greater than zero (Amount.of). */
    class InvalidAmountException(message: String) : PaymentDomainException(message)

    /** A currency code must be 3 capital letters (ISO 4217, e.g. EUR). */
    class InvalidCurrencyException(message: String) : PaymentDomainException(message)

    class SplitValidationException(message: String) : PaymentDomainException(message)
}
