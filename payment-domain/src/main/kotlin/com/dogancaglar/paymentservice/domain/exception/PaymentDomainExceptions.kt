package com.dogancaglar.paymentservice.domain.exception

class MissingPaymentOrderException(
    val paymentOrderId: Long,
    message: String = "PaymentOrder row is missing for id=$paymentOrderId"
) : RuntimeException(message)
