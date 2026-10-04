package com.dogancaglar.paymentservice.domain.exception

/** No payment intent with this id for this merchant (another merchant's is "not found" too). */
class PaymentIntentNotFoundException(message: String) : NonRetryableException(message)
