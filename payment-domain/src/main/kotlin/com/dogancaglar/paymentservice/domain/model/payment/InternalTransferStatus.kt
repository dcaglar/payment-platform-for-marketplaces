package com.dogancaglar.paymentservice.domain.model.payment

enum class InternalTransferStatus {
    CREATED_PENDING,
    SENT_FOR_TRANSFER,
    TRANSFERRED,
    PARTIALLY_REVERSED,
    REVERSED
}
