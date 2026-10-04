package com.dogancaglar.paymentservice.domain.model.payment

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.domain.model.vo.*
import kotlin.test.*

class PaymentTest {

    private val currency = Currency("EUR")
    private val buyerId = BuyerId("b1")
    private val line1 = PaymentSplit.of(LedgerAccountType.SELLER_PAYABLE, "s1", Amount.of(500, currency))
    private val line2 = PaymentSplit.of(LedgerAccountType.SELLER_PAYABLE, "s2", Amount.of(500, currency))
    private val lines = listOf(line1, line2)
    private val totalAmount = Amount.of(1000, currency)

    private fun createPayment(): Payment =
        Payment.initializeFromAuthEvent(
            paymentId = PaymentId(1),
            paymentIntentId = PaymentIntentId(100),
            buyerId = buyerId,
            merchantAccount = "merchant-1",
            processingModel = ProcessingModel.MARKETPLACE,
            totalAmount = totalAmount,
            splits = lines
        )

    @Test
    fun `initializeFromAuthEvent creates payment correctly`() {
        val payment = createPayment()

        assertEquals(PaymentStatus.AUTHORIZED, payment.status)
        assertEquals(Amount.zero(currency), payment.capturedAmount)
        assertEquals(Amount.zero(currency), payment.refundedAmount)
    }

    @Test
    fun `applyCapture transitions SENT_FOR_SETTLE to PARTIALLY_CAPTURED`() {
        val payment = createPayment().markSentForSettle()
        val captured = payment.applyCapture(Amount.of(400, currency))

        assertEquals(Amount.of(400, currency), captured.capturedAmount)
        assertEquals(PaymentStatus.PARTIALLY_CAPTURED, captured.status)
    }

    @Test
    fun `applyCapture transitions to CAPTURED when fully captured`() {
        val payment = createPayment().markSentForSettle()
        val captured = payment.applyCapture(Amount.of(1000, currency))

        assertEquals(PaymentStatus.CAPTURED, captured.status)
    }

    @Test
    fun `applyCapture fails when amount exceeds total`() {
        val payment = createPayment().markSentForSettle()

        assertFailsWith<IllegalArgumentException> {
            payment.applyCapture(Amount.of(2000, currency))
        }
    }

    @Test
    fun `applyRefund transitions partially captured to partially refunded`() {
        val payment = createPayment()
            .markSentForSettle()
            .applyCapture(Amount.of(800, currency))

        val refunded = payment.applyRefund(Amount.of(300, currency))

        assertEquals(Amount.of(300, currency), refunded.refundedAmount)
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, refunded.status)
    }

    @Test
    fun `applyRefund transitions to fully refunded`() {
        val payment = createPayment()
            .markSentForSettle()
            .applyCapture(Amount.of(800, currency))

        val refunded = payment.applyRefund(Amount.of(800, currency))

        assertEquals(PaymentStatus.REFUNDED, refunded.status)
    }

    @Test
    fun `applyRefund fails when refund exceeds captured`() {
        val payment = createPayment()
            .markSentForSettle()
            .applyCapture(Amount.of(500, currency))

        assertFailsWith<IllegalArgumentException> {
            payment.applyRefund(Amount.of(600, currency))
        }
    }

    // -------------------------------
    // VOID AUTHORIZATION TESTS
    // -------------------------------

    @Test
    fun `voidAuthorization transitions AUTHORIZED to VOIDED`() {
        val payment = createPayment()

        val voided = payment.voidAuthorization()

        assertEquals(PaymentStatus.VOIDED, voided.status)
        assertEquals(Amount.zero(currency), voided.capturedAmount)
        assertEquals(Amount.zero(currency), voided.refundedAmount)
    }

    @Test
    fun `voidAuthorization fails if payment already partially captured`() {
        val payment = createPayment()
            .markSentForSettle()
            .applyCapture(Amount.of(200, currency))

        assertFailsWith<IllegalArgumentException> {
            payment.voidAuthorization()
        }
    }

    @Test
    fun `voidAuthorization fails if payment is already captured`() {
        val payment = createPayment()
            .markSentForSettle()
            .applyCapture(Amount.of(1000, currency))

        assertFailsWith<IllegalArgumentException> {
            payment.voidAuthorization()
        }
    }

    @Test
    fun `voidAuthorization fails if already voided`() {
        val payment = createPayment()
        val voided = payment.voidAuthorization()

        assertFailsWith<IllegalArgumentException> {
            voided.voidAuthorization()
        }
    }
}
