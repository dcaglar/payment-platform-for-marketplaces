package com.dogancaglar.paymentservice.application.util

import com.dogancaglar.common.id.PublicIdFactory
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId

fun PaymentIntentId.toPublicPaymentIntentId(): String =
    PublicIdFactory.publicPaymentIntentId(this.value)

fun PaymentId.toPublicPaymentId(): String =
    PublicIdFactory.publicPaymentId(this.value)
