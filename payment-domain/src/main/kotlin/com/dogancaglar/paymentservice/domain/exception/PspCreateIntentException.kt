package com.dogancaglar.paymentservice.domain.exception

sealed class PspCreateIntentException(message: String, cause: Throwable?) : RuntimeException(message, cause)
class PspTransientException(message: String, cause: Throwable?) : PspCreateIntentException(message, cause)
class PspPermanentException(message: String, cause: Throwable?) : PspCreateIntentException(message, cause)
