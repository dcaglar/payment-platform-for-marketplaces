package com.dogancaglar.common.db.converter

import com.dogancaglar.common.db.entity.PaymentIntentEntity
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.payment.CardBrand
import com.dogancaglar.paymentservice.domain.model.payment.CardSummary
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus
import com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.OrderId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId

object PaymentIntentEntityMapper {

    fun toDomain(entity: PaymentIntentEntity, splitsDelegate: Lazy<List<PaymentSplit>>): PaymentIntent {
        return PaymentIntent.rehydrate(
            paymentIntentId = PaymentIntentId(entity.paymentIntentId),
            pspReference = entity.pspReference,
            buyerId = BuyerId(entity.buyerId),
            orderId = OrderId(entity.orderId),
            merchantAccount = entity.merchantAccount,
            processingModel = ProcessingModel.valueOf(entity.processingModel),
            totalAmount = Amount.of(entity.totalAmountValue, Currency(entity.currency)),
            splitsDelegate = splitsDelegate,
            status = PaymentIntentStatus.valueOf(entity.status),
            createdAt = Utc.fromInstant(entity.createdAt),
            updatedAt = Utc.fromInstant(entity.updatedAt),
            cardSummary = cardSummaryOf(entity.cardBrand, entity.cardLast4)
        )
    }

    /** Both columns or neither: a stored card summary is brand + last 4. */
    fun cardSummaryOf(brand: String?, last4: String?): CardSummary? {
        if (brand == null || last4 == null) {
            return null
        }
        return CardSummary.of(CardBrand.valueOf(brand), last4)
    }

    fun toEntity(domain: PaymentIntent, splitsJson: String): PaymentIntentEntity {
        return PaymentIntentEntity(
            paymentIntentId = domain.paymentIntentId.value,
            pspReference = domain.pspReference,
            buyerId = domain.buyerId.value,
            orderId = domain.orderId.value,
            merchantAccount = domain.merchantAccount,
            processingModel = domain.processingModel.name,
            totalAmountValue = domain.totalAmount.quantity,
            currency = domain.totalAmount.currency.currencyCode,
            status = domain.status.name,
            createdAt = Utc.toInstant(domain.createdAt),
            updatedAt = Utc.toInstant(domain.updatedAt),
            splitsJson = splitsJson,
            cardBrand = domain.cardSummary?.brand?.name,
            cardLast4 = domain.cardSummary?.last4
        )
    }
}
