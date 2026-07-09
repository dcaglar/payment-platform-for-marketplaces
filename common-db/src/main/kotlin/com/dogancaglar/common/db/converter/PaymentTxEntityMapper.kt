package com.dogancaglar.common.db.converter

import com.dogancaglar.paymentservice.domain.model.ledger.SettleStatus
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.ledger.TxStatus
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.common.db.entity.PaymentTxEntity
import java.time.Instant

object PaymentTxEntityMapper {
     fun toEntity(domain: Tx): PaymentTxEntity = when (domain) {

        is Tx.AuthorizationTx -> PaymentTxEntity(
            txId              = domain.txId.value,
            txType            = domain.txType.name,
            paymentId         = domain.paymentId.value,
            paymentIntentId   = domain.paymentIntentId.value,
            parentTxId        = null,
            acquirerReference = domain.acquirerReference,
            amountValue       = domain.amount.quantity,
            amountCurrency    = domain.amount.currency.currencyCode,
            status            = domain.status.name,
            settleStatus      = null,
            acquirerBatchRef  = null,
            settledAmountValue = null,
            createdAt         = domain.createdAt
        )

        is Tx.CaptureTx -> PaymentTxEntity(
            txId              = domain.txId.value,
            txType            = domain.txType.name,
            paymentId         = domain.paymentId.value,
            paymentIntentId   = domain.paymentIntentId.value,
            parentTxId        = domain.authorizationTxId.value,
            acquirerReference = domain.acquirerReference,
            amountValue       = domain.amount.quantity,
            amountCurrency    = domain.amount.currency.currencyCode,
            status            = domain.status.name,
            settleStatus      = domain.settleStatus.name,
            acquirerBatchRef  = null,
            settledAmountValue = null,
            createdAt         = domain.createdAt
        )

        is Tx.RefundTx -> PaymentTxEntity(
            txId              = domain.txId.value,
            txType            = domain.txType.name,
            paymentId         = domain.paymentId.value,
            paymentIntentId   = domain.paymentIntentId.value,
            parentTxId        = domain.captureTxId.value,
            acquirerReference = domain.acquirerReference,
            amountValue       = domain.amount.quantity,
            amountCurrency    = domain.amount.currency.currencyCode,
            status            = domain.status.name,
            settleStatus      = null,
            acquirerBatchRef  = null,
            settledAmountValue = null,
            createdAt         = domain.createdAt
        )

         is Tx.SettleTx -> PaymentTxEntity(
             txId               = domain.txId.value,
             txType             = domain.txType.name,
             paymentId          = domain.paymentId.value,
             paymentIntentId    = domain.paymentIntentId.value,
             parentTxId         = domain.captureTxId.value,
             acquirerReference  = domain.acquirerBatchReference,
             amountValue        = domain.amount.quantity,
             amountCurrency     = domain.amount.currency.currencyCode,
             status             = domain.status.name,
             settleStatus       = domain.settleStatus.name, // Dumb translation 🟢
             acquirerBatchRef   = domain.acquirerBatchReference,
             settledAmountValue = domain.grossAmount.quantity,
             createdAt          = domain.createdAt
         )
        is Tx.PayoutTx -> PaymentTxEntity(
            txId              = domain.txId.value,
            txType            = domain.txType.name,
            paymentId         = domain.paymentId.value,
            paymentIntentId   = domain.paymentIntentId.value,
            parentTxId        = null,
            acquirerReference = domain.payoutBatchReference,
            amountValue       = domain.amount.quantity,
            amountCurrency    = domain.amount.currency.currencyCode,
            status            = domain.status.name,
            settleStatus      = null,
            acquirerBatchRef  = null,
            settledAmountValue = null,
            createdAt         = domain.createdAt
        )

        is Tx.PspFeeTx -> PaymentTxEntity(
            txId              = domain.txId.value,
            txType            = domain.txType.name,
            paymentId         = domain.paymentId.value,
            paymentIntentId   = domain.paymentIntentId.value,
            parentTxId        = domain.parentTxId.value,
            acquirerReference = "",
            amountValue       = domain.amount.quantity,
            amountCurrency    = domain.amount.currency.currencyCode,
            status            = domain.status.name,
            settleStatus      = null,
            acquirerBatchRef  = null,
            settledAmountValue = null,
            createdAt         = domain.createdAt
        )
    }

    fun toDomain(entity: PaymentTxEntity): Tx {
        val amount    = Amount.of(entity.amountValue, Currency(entity.amountCurrency))
        val createdAt = entity.createdAt ?: Instant.now()
        val status    = TxStatus.valueOf(entity.status)
        val paymentIntentId = PaymentIntentId(entity.paymentIntentId)

        return when (entity.txType) {

            "AUTHORIZATION" -> Tx.AuthorizationTx(
                txId              = TxId(entity.txId),
                paymentId         = PaymentId(entity.paymentId),
                paymentIntentId   = paymentIntentId,
                acquirerReference = entity.acquirerReference,
                amount            = amount,
                status            = status,
                createdAt         = createdAt
            )

            "CAPTURE" -> Tx.CaptureTx(
                txId              = TxId(entity.txId),
                paymentId         = PaymentId(entity.paymentId),
                paymentIntentId   = paymentIntentId,
                authorizationTxId = TxId(requireNotNull(entity.parentTxId) {
                    "CAPTURE row txId=${entity.txId} is missing parentTxId (authorizationTxId)"
                }),
                acquirerReference = entity.acquirerReference,
                amount            = amount,
                status            = status,
                settleStatus      = entity.settleStatus
                    ?.let { SettleStatus.valueOf(it) }
                    ?: SettleStatus.UNMATCHED,
                createdAt         = createdAt
            )

            "REFUND" -> Tx.RefundTx(
                txId              = TxId(entity.txId),
                paymentId         = PaymentId(entity.paymentId),
                paymentIntentId   = paymentIntentId,
                captureTxId       = TxId(requireNotNull(entity.parentTxId) {
                    "REFUND row txId=${entity.txId} is missing parentTxId (captureTxId)"
                }),
                acquirerReference = entity.acquirerReference,
                amount            = amount,
                status            = status,
                createdAt         = createdAt
            )

            "SETTLE", "SETTLEMENT" -> Tx.SettleTx(
                txId                   = TxId(entity.txId),
                paymentId              = PaymentId(entity.paymentId),
                paymentIntentId        = paymentIntentId,
                captureTxId            = TxId(requireNotNull(entity.parentTxId) {
                    "SETTLE row txId=${entity.txId} is missing parentTxId (captureTxId)"
                }),
                acquirerBatchReference = requireNotNull(entity.acquirerBatchRef) {
                    "SETTLE row txId=${entity.txId} is missing txId"
                },
                grossAmount            = Amount.of(
                    requireNotNull(entity.settledAmountValue) {
                        "SETTLE row txId=${entity.txId} is missing settledAmountValue"
                    },
                    Currency(entity.amountCurrency)
                ),
                feeAmount              = Amount.of(0L, Currency(entity.amountCurrency)),
                netCashAmount          = Amount.of(
                    requireNotNull(entity.settledAmountValue) {
                        "SETTLE row txId=${entity.txId} is missing settledAmountValue"
                    },
                    Currency(entity.amountCurrency)
                ),
                amount                 = amount,
                settleStatus           = entity.settleStatus?.let { SettleStatus.valueOf(it) } ?: SettleStatus.UNMATCHED,
                status                 = status,
                createdAt              = createdAt
            )

            "PAYOUT" -> Tx.PayoutTx(
                txId              = TxId(entity.txId),
                paymentId         = PaymentId(entity.paymentId),
                paymentIntentId   = paymentIntentId,
                merchantEntityId  = "",
                payoutBatchReference = entity.acquirerReference,
                amount            = amount,
                status            = status,
                createdAt         = createdAt
            )

            "PSP_FEE" -> Tx.PspFeeTx(
                txId              = TxId(entity.txId),
                paymentId         = PaymentId(entity.paymentId),
                paymentIntentId   = paymentIntentId,
                parentTxId        = TxId(requireNotNull(entity.parentTxId) {
                    "PSP_FEE row txId=${entity.txId} is missing parentTxId"
                }),
                amount            = amount,
                status            = status,
                createdAt         = createdAt
            )

            else -> throw IllegalStateException(
                "Unknown Tx type ${entity.txType} for txId={entity.txId}."
            )
        }
    }
}