package com.dogancaglar.paymentservice.domain.exception

/** The PSP call that failed. */
enum class PspOperation { CREATE_INTENT, AUTHORIZE, RETRIEVE_CLIENT_SECRET, CAPTURE, REFUND }

/**
 * A PSP call that did not give a normal answer. Every PSP adapter (HTTP, Stripe, simulators) translates whatever its
 * PSP does into one of the three classes below and says which [operation] failed for which payment; nothing after the
 * adapter sees the PSP's own errors. Whether to retry comes from each class's family ([RetryableException] /
 * [NonRetryableException]); callers catch the concrete classes.
 *
 * A decline is not one of these: it's an answer (the intent is marked DECLINED).
 */
sealed interface ExternalPspException {
    val operation: PspOperation
    val paymentIntentId: Long
}

/** Not done, and may work next time (PSP not reached, rate limited, temporarily unavailable): retry. */
class PspTransientException(
    override val operation: PspOperation,
    override val paymentIntentId: Long,
    message: String,
    cause: Throwable? = null
) : RetryableException("PSP $operation for paymentIntentId=$paymentIntentId: $message", cause), ExternalPspException

/**
 * May have been done: the request may have reached the PSP, but we have no usable answer (timeout, connection lost
 * after sending, PSP server error). Retry only with the same idempotency key (every PSP call carries one).
 */
class PspUnknownException(
    override val operation: PspOperation,
    override val paymentIntentId: Long,
    message: String,
    cause: Throwable? = null
) : RetryableException("PSP $operation for paymentIntentId=$paymentIntentId: $message", cause), ExternalPspException

/**
 * Refused, and won't work on retry: our request or our configuration is wrong (invalid request, bad API key).
 * Don't retry; it needs attention.
 */
class PspPermanentException(
    override val operation: PspOperation,
    override val paymentIntentId: Long,
    message: String,
    cause: Throwable? = null
) : NonRetryableException("PSP $operation for paymentIntentId=$paymentIntentId: $message", cause), ExternalPspException
