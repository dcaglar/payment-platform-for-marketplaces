package com.dogancaglar.paymentservice.domain.exception

/**
 * Every exception the platform throws on purpose. Its family says by itself whether retrying can help:
 * [RetryableException] or [NonRetryableException]. Handlers (controller advice, Kafka error handler, services)
 * decide by family and type, never by catching `Exception`. See docs/code-health/exception-hierarchy.md.
 *
 * Abstract, not sealed: other modules (e.g. the back office in payment-consumers) extend the two families.
 */
abstract class PaymentPlatformException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/** Not done, and may work later: retrying (the same request, later) can succeed. */
abstract class RetryableException(
    message: String,
    cause: Throwable? = null
) : PaymentPlatformException(message, cause)

/**
 * Won't work on retry: the caller's request is wrong ([RequestValidationException], not found, key reused) or we are
 * (an invariant, a transition or the ledger was violated: a bug, needs attention).
 */
abstract class NonRetryableException(
    message: String,
    cause: Throwable? = null
) : PaymentPlatformException(message, cause)
