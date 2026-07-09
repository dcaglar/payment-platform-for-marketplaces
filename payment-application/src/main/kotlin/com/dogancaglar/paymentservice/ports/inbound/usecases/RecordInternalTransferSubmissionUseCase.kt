package com.dogancaglar.paymentservice.ports.inbound.usecases

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId

interface RecordInternalTransferSubmissionUseCase {
    fun recordSubmission(
        paymentId: PaymentId,
        paymentIntentId: PaymentIntentId,
        paymentMerchantAccountId:String,
        sourceAccount: String,
        targetAccount: String,
        transferAmount : Amount,
        journalType: JournalType,
        reason:String
    )
}