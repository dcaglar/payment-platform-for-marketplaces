package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.ledger.Tx

interface PaymentTxPort {
    fun save(tx: Tx)
    fun findByPaymentId(paymentId: Long): List<Tx>

    /** The payment's txs, oldest first, only if the payment is this merchant's (else empty). */
    fun findByPaymentIdForMerchant(paymentId: Long, merchantAccount: String): List<Tx>

    /** The tx, only if its payment is this merchant's (else null). */
    fun findByTxIdForMerchant(txId: Long, merchantAccount: String): Tx?
}
