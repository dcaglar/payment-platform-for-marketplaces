package com.dogancaglar.paymentservice.domain.exception

sealed class PaymentValidationException(message: String) : RuntimeException(message)

class PspInvalidPaymentException(mesage: String) : PaymentValidationException(mesage)

class PaymentNotReadyException(mesage: String) : PaymentValidationException(mesage)
