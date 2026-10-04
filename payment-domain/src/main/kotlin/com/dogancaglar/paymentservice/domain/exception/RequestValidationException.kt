package com.dogancaglar.paymentservice.domain.exception

/** The caller's request is wrong: fix the request, retrying it unchanged gets the same answer (400). */
sealed class RequestValidationException(message: String) : NonRetryableException(message)

/** The payment method can't be sent to the PSP (e.g. not a card token). */
class PspInvalidPaymentException(message: String) : RequestValidationException(message)
