package com.dogancaglar.paymentservice.domain.exception

/** A request with the same idempotency key is still being processed: retry later with the same key. */
class IdempotencyKeyInProgressException(message: String) : RuntimeException(message)

/** The idempotency key was already used with a different request body: a new request needs a new key. */
class IdempotencyKeyReusedException(message: String) : RuntimeException(message)
