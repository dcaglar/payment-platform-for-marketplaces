package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import com.dogancaglar.paymentservice.ports.inbound.usecases.TxUseCase
import com.dogancaglar.paymentservice.ports.outbound.JournalEntryRepository
import com.dogancaglar.paymentservice.ports.outbound.PaymentTxPort

class TxService(
    private val paymentTxPort: PaymentTxPort,
    private val journalEntryRepository: JournalEntryRepository
) : TxUseCase {

    override fun findTxsOfPayment(paymentId: PaymentId, merchantAccount: String): List<Tx> =
        paymentTxPort.findByPaymentIdForMerchant(paymentId.value, merchantAccount)

    override fun findJournalEntriesOfPayment(paymentId: PaymentId, merchantAccount: String): List<JournalEntry> {
        // every recorded payment has its authorization tx: no tx means not this merchant's payment (or unknown)
        if (paymentTxPort.findByPaymentIdForMerchant(paymentId.value, merchantAccount).isEmpty()) {
            return emptyList()
        }
        return journalEntryRepository.findByPaymentId(paymentId)
    }

    override fun getTx(txId: TxId, merchantAccount: String): Tx? =
        paymentTxPort.findByTxIdForMerchant(txId.value, merchantAccount)

    override fun findJournalEntriesOfTx(txId: TxId, merchantAccount: String): List<JournalEntry> {
        if (paymentTxPort.findByTxIdForMerchant(txId.value, merchantAccount) == null) {
            return emptyList()
        }
        return journalEntryRepository.findByTxId(txId)
    }
}
