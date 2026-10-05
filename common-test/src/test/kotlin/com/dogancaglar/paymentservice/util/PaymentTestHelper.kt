package com.dogancaglar.paymentservice.util

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.OrderId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId

object PaymentTestHelper {

    @Suppress("LongParameterList") // test helper: a default for each field
    fun createPaymentIntent(
        paymentIntentId: Long = 100L,
        buyerId: String = "buyer-123",
        orderId: String = "order-456",
        totalAmount: Long = 10_000,
        currency: String = "EUR",
        sellerId: String = "seller-789",
        merchantAccount: String = "merchant-001",
        processingModel: ProcessingModel = ProcessingModel.DIRECT_MERCHANT
    ): PaymentIntent {
        val amount = Amount.of(totalAmount, Currency(currency))
        return PaymentIntent.createNew(
            paymentIntentId = PaymentIntentId(paymentIntentId),
            buyerId = BuyerId(buyerId),
            orderId = OrderId(orderId),
            merchantAccount = merchantAccount,
            processingModel = processingModel,
            totalAmount = amount,
            splits = if (processingModel == ProcessingModel.MARKETPLACE) {
                listOf(
                    PaymentSplit.of(
                        accountType = LedgerAccountType.SELLER_PAYABLE,
                        account = sellerId,
                        amount = amount
                    )
                )
            } else {
                emptyList()
            }
        )
    }
}
