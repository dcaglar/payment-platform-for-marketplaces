package com.dogancaglar.paymentservice.event

import com.dogancaglar.common.event.EventEnvelopeFactory
import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.logging.GenericLogFields
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.application.events.CaptureRequested
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.MDC
import java.util.UUID

class DomainEventFactoryTest {

    @Test
    fun `should create EventEnvelope with generated eventId`() {
        // given
        try {
            val now = Utc.nowInstant()
            val event = CaptureRequested.from(
                paymentIntent = com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent.createNew(
                    paymentIntentId = com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId(1001L),
                    buyerId = com.dogancaglar.paymentservice.domain.model.vo.BuyerId("buyer_1"),
                    orderId = com.dogancaglar.paymentservice.domain.model.vo.OrderId("order_1"),
                    processingModel = com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel.DIRECT_MERCHANT,
                    merchantAccount = "m_1",
                    totalAmount = com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR")),
                    splits = listOf(com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit.of(com.dogancaglar.paymentservice.domain.model.ledger.AccountType.MARKETPLACE_SELLER_BALANCE_ACCOUNT, "m_1", com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR"))))
                ),
                captureAmount = com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR")),
                timestamp = now
            )
            
            // when
            val envelope: EventEnvelope<CaptureRequested> = EventEnvelopeFactory.envelopeFor(
                data = event,
                aggregateId = event.publicPaymentIntentId
            )

            // then
            Assertions.assertThat(envelope.eventId).isNotNull
            Assertions.assertThat(envelope.eventType).isEqualTo("capture_requested")
        } finally {
            MDC.clear()
        }
    }

    @Test
    fun `should generate unique eventId each time`() {
        try {
            val now = Utc.nowInstant()
            val event1 = CaptureRequested.from(
                paymentIntent = com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent.createNew(
                    paymentIntentId = com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId(1001L),
                    buyerId = com.dogancaglar.paymentservice.domain.model.vo.BuyerId("buyer_1"),
                    orderId = com.dogancaglar.paymentservice.domain.model.vo.OrderId("order_1"),
                    processingModel = com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel.DIRECT_MERCHANT,
                    merchantAccount = "m_1",
                    totalAmount = com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR")),
                    splits = listOf(com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit.of(com.dogancaglar.paymentservice.domain.model.ledger.AccountType.MARKETPLACE_SELLER_BALANCE_ACCOUNT, "m_1", com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR"))))
                ),
                captureAmount = com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR")),
                timestamp = now
            )
            val envelope1 = EventEnvelopeFactory.envelopeFor(
                data = event1,
                aggregateId = event1.publicPaymentIntentId
            )
            
            val event2 = CaptureRequested.from(
                paymentIntent = com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent.createNew(
                    paymentIntentId = com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId(1002L),
                    buyerId = com.dogancaglar.paymentservice.domain.model.vo.BuyerId("buyer_1"),
                    orderId = com.dogancaglar.paymentservice.domain.model.vo.OrderId("order_1"),
                    processingModel = com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel.DIRECT_MERCHANT,
                    merchantAccount = "m_1",
                    totalAmount = com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR")),
                    splits = listOf(com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit.of(com.dogancaglar.paymentservice.domain.model.ledger.AccountType.MARKETPLACE_SELLER_BALANCE_ACCOUNT, "m_1", com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR"))))
                ),
                captureAmount = com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR")),
                timestamp = now
            )
            val envelope2 = EventEnvelopeFactory.envelopeFor(
                data = event2,
                aggregateId = event2.publicPaymentIntentId
            )
            
            Assertions.assertThat(envelope1.eventId).isNotEqualTo(envelope2.eventId)

        } finally {
            MDC.clear()
        }
    }

    @Test
    fun `envelopeFor should create EventEnvelope with correct fields`() {
        try {
            val now = Utc.nowInstant()
            val event = CaptureRequested.from(
                paymentIntent = com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent.createNew(
                    paymentIntentId = com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId(1001L),
                    buyerId = com.dogancaglar.paymentservice.domain.model.vo.BuyerId("buyer_1"),
                    orderId = com.dogancaglar.paymentservice.domain.model.vo.OrderId("order_1"),
                    processingModel = com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel.DIRECT_MERCHANT,
                    merchantAccount = "m_1",
                    totalAmount = com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR")),
                    splits = listOf(com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit.of(com.dogancaglar.paymentservice.domain.model.ledger.AccountType.MARKETPLACE_SELLER_BALANCE_ACCOUNT, "m_1", com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR"))))
                ),
                captureAmount = com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR")),
                timestamp = now
            )

            val envelope = EventEnvelopeFactory.envelopeFor(
                data = event,
                aggregateId = event.publicPaymentIntentId
            )

            Assertions.assertThat(envelope.eventId).isNotNull
            Assertions.assertThat(envelope.eventType).isEqualTo("capture_requested")
            Assertions.assertThat(envelope.aggregateId).isEqualTo(event.publicPaymentIntentId)
            Assertions.assertThat(envelope.data).isEqualTo(event)
        } finally {
            MDC.clear()
        }
    }

    @Test
    fun `should set parentEventId when provided`() {
        try {
            val parentId = UUID.randomUUID().toString()
            val now = Utc.nowInstant()
            val event = CaptureRequested.from(
                paymentIntent = com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent.createNew(
                    paymentIntentId = com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId(1001L),
                    buyerId = com.dogancaglar.paymentservice.domain.model.vo.BuyerId("buyer_1"),
                    orderId = com.dogancaglar.paymentservice.domain.model.vo.OrderId("order_1"),
                    processingModel = com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel.DIRECT_MERCHANT,
                    merchantAccount = "m_1",
                    totalAmount = com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR")),
                    splits = listOf(com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit.of(com.dogancaglar.paymentservice.domain.model.ledger.AccountType.MARKETPLACE_SELLER_BALANCE_ACCOUNT, "m_1", com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR"))))
                ),
                captureAmount = com.dogancaglar.paymentservice.domain.model.common.Amount.of(1000L, com.dogancaglar.paymentservice.domain.model.common.Currency("EUR")),
                timestamp = now
            )

            val envelope = EventEnvelopeFactory.envelopeFor(
                data = event,
                aggregateId = event.publicPaymentIntentId,
                parentEventId = parentId
            )
            Assertions.assertThat(envelope.parentEventId).isEqualTo(parentId)
        } finally {
            MDC.clear()
        }
    }

}