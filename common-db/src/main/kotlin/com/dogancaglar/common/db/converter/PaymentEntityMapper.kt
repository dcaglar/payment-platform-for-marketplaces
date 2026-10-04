package com.dogancaglar.common.db.converter

import com.dogancaglar.common.db.entity.PaymentEntity
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.payment.Payment
import com.dogancaglar.paymentservice.domain.model.payment.PaymentStatus
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId

object PaymentEntityMapper {

    fun toDomain(entity: PaymentEntity, splits: List<com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit>): Payment {
        val currency = Currency(entity.currency)
        val total = Amount.of(entity.totalAmountValue, currency)

        val captured = if (entity.capturedAmountValue == 0L) {
            Amount.zero(currency)
        } else {
            Amount.of(entity.capturedAmountValue, currency)
        }

        val refunded = if (entity.refundedAmountValue == 0L) {
            Amount.zero(currency)
        } else {
            Amount.of(entity.refundedAmountValue, currency)
        }

        return Payment.rehydrate(
            paymentId = PaymentId(entity.paymentId),
            paymentIntentId = PaymentIntentId(entity.paymentIntentId),
            buyerId = BuyerId(entity.buyerId),
            merchantAccount = entity.merchantAccount,
            processingModel = ProcessingModel.valueOf(entity.processingModel),
            totalAmount = total,
            capturedAmount = captured,
            refundedAmount = refunded,
            status = PaymentStatus.valueOf(entity.status),
            splits = splits,
            createdAt = Utc.fromInstant(entity.createdAt),
            updatedAt = Utc.fromInstant(entity.updatedAt)
        )
    }

    fun toEntity(domain: Payment, splitsJson: String): PaymentEntity {
        return PaymentEntity(
            paymentId = domain.paymentId.value,
            paymentIntentId = domain.paymentIntentId.value,
            buyerId = domain.buyerId.value,
            merchantAccount = domain.merchantAccount,
            processingModel = domain.processingModel.name,
            totalAmountValue = domain.totalAmount.quantity,
            currency = domain.totalAmount.currency.currencyCode,
            capturedAmountValue = domain.capturedAmount.quantity,
            refundedAmountValue = domain.refundedAmount.quantity,
            status = domain.status.name,
            splitsJson = splitsJson,
            createdAt = Utc.toInstant(domain.createdAt),
            updatedAt = Utc.toInstant(domain.updatedAt)
        )
    }
}
