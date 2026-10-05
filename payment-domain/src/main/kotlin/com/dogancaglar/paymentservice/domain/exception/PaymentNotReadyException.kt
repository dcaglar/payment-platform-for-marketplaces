package com.dogancaglar.paymentservice.domain.exception

/** Authorize was called before the PSP created the payment intent: retry later (409 + Retry-After). */
class PaymentNotReadyException(message: String) : RetryableException(message)
