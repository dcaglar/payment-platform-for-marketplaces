package com.dogancaglar.paymentservice.domain.exception

/** An outbox event rule was violated: our bug → non-retryable. */
sealed class OutboxEventDomainException(message: String) : NonRetryableException(message) {

    /** The outbox event's state machine (NEW → PROCESSING → SENT) doesn't allow this status change. */
    class InvalidStateTransitionException(message: String) : OutboxEventDomainException(message)
}
