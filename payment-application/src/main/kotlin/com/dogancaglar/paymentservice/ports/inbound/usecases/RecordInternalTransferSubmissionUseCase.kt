package com.dogancaglar.paymentservice.ports.inbound.usecases

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.payment.Payment

interface RecordInternalTransferSubmissionUseCase {
    fun recordSubmission(
        payment: Payment,
        sourceAccount: String,
        targetAccount: String,
        transferAmount: Amount,
        journalType: JournalType,
        reason: String
    )
}
