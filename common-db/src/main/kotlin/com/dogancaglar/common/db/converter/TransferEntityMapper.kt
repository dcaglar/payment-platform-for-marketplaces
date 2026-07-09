package com.dogancaglar.common.db.converter

import com.dogancaglar.common.db.entity.TransferEntity
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import com.dogancaglar.paymentservice.domain.model.payment.InternalTransfer
import com.dogancaglar.paymentservice.domain.model.payment.InternalTransferStatus
import com.dogancaglar.paymentservice.domain.model.vo.InternalTransferId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId

object TransferEntityMapper {

    fun toDomain(entity: TransferEntity): InternalTransfer {
        val currency = Currency(entity.currency)
        val amount = if (entity.amountValue == 0L) {
            Amount.zero(currency)
        } else {
            Amount.of(entity.amountValue, currency)
        }
        return InternalTransfer.rehydrate(
            transferId          = InternalTransferId(entity.transferId),
            paymentId = PaymentId(entity.paymentId),
            paymentIntentId = PaymentIntentId(entity.paymentIntentId),
            merchantAccountId = entity.merchantAccountId,
            amount              = amount,
            sourceAccount       = entity.sourceAccount,
            targetAccount       = entity.targetAccount,
            transferType        = entity.transferType,
            status              = InternalTransferStatus.valueOf(entity.status),
            createdAt           = entity.createdAt,
            updatedAt           = entity.updatedAt
        )
    }

    fun toEntity(domain: InternalTransfer): TransferEntity {
        return TransferEntity(
            transferId          = domain.transferId.value,
            paymentIntentId = domain.paymentIntentId.value,
            paymentId =domain.paymentId.value,
            merchantAccountId = domain.merchantAccountId,
            amountValue         = domain.amount.quantity,
            currency            = domain.amount.currency.currencyCode,
            sourceAccount       = domain.sourceAccount,
            targetAccount       = domain.targetAccount,
            transferType        = domain.transferType,
            status              = domain.status.name,
            createdAt           = domain.createdAt,
            updatedAt           = domain.updatedAt
        )
    }
}
