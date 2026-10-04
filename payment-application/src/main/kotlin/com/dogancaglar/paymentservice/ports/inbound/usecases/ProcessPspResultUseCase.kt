package com.dogancaglar.paymentservice.ports.inbound.usecases

import com.dogancaglar.paymentservice.application.events.CaptureConfirmed
import com.dogancaglar.paymentservice.application.events.InternalTransferCommand
import com.dogancaglar.paymentservice.application.events.PaymentAuthorized
import com.dogancaglar.paymentservice.application.events.SettlementReceived

interface ProcessPspResultUseCase {

    fun processAuthorized(event: PaymentAuthorized)

    fun processCaptureConfirmed(event: CaptureConfirmed)
    fun processInternalTransferCommand(event: InternalTransferCommand)
    fun processSettlementLineReconciled(event: SettlementReceived)
}
