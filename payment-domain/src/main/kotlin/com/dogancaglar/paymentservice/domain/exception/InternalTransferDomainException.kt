package com.dogancaglar.paymentservice.domain.exception

/** A rule of an internal transfer was violated, or the transfer is missing: our bug → non-retryable, DLQ. */
sealed class InternalTransferDomainException(message: String) : NonRetryableException(message) {

    /** No internal transfer with this id, although an event refers to it. */
    class TransferNotFoundException(message: String) : InternalTransferDomainException(message)

    /** A required value is missing or wrong (blank account, amount ≤ 0, invalid id). */
    class InvariantViolationException(message: String) : InternalTransferDomainException(message)

    /** The transfer's state machine doesn't allow this status change. */
    class InvalidStateTransitionException(message: String) : InternalTransferDomainException(message)
}
