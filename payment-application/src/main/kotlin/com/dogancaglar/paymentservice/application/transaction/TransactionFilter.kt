package com.dogancaglar.paymentservice.application.transaction

import com.dogancaglar.paymentservice.domain.model.payment.PaymentStatus
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import java.time.Instant

/** What the back office searches transactions by. Null = do not filter on it. */
data class TransactionFilter(
    val merchantAccount: String? = null,
    val orderId: String? = null,
    val paymentId: PaymentId? = null,
    val sellerId: String? = null,
    val status: PaymentStatus? = null,
    val authorizedFrom: Instant? = null,
    val authorizedTo: Instant? = null,
    val processingModel: ProcessingModel? = null // direct sale or marketplace
)
