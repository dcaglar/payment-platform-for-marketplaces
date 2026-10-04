package com.dogancaglar.paymentservice.domain.exception

class PspUnknownException(message: String, cause: Throwable?) : PspCreateIntentException(message, cause)
