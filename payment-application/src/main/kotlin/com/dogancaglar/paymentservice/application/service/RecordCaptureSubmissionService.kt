package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.application.events.CaptureConfirmed
import com.dogancaglar.paymentservice.application.events.CaptureSubmitted
import com.dogancaglar.paymentservice.application.events.SettlementReceived
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.ledger.TxStatus.PENDING
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import com.dogancaglar.paymentservice.ports.inbound.usecases.RecordCaptureSubmissionUseCase
import com.dogancaglar.paymentservice.ports.outbound.CentralDbTransactionalFacadePort
import com.dogancaglar.paymentservice.ports.outbound.IdGeneratorPort
import com.dogancaglar.paymentservice.ports.outbound.MerchantAccountRepository
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import com.dogancaglar.paymentservice.ports.outbound.PaymentRepository
import com.dogancaglar.paymentservice.ports.outbound.PaymentTxPort
import org.slf4j.LoggerFactory

open class RecordCaptureSubmissionService(
    private val centralDbTransactionalFacadePort: CentralDbTransactionalFacadePort,
    private val paymentRepository: PaymentRepository,
    private val paymentTxPort: PaymentTxPort,
    private val idGeneratorPort: IdGeneratorPort,
    private val outboxEventFactoryPort: OutboxEventFactoryPort,
    private val merchantAccountRepository: MerchantAccountRepository
) : RecordCaptureSubmissionUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun recordSubmission(event: CaptureSubmitted, parentEventId: String) {
        val paymentIntentId = PaymentIntentId(event.paymentIntentId.toLongOrNull() ?: 0L)
        val payment = paymentRepository.findByPaymentIntentId(paymentIntentId)
            ?: throw IllegalStateException("Payment context aggregate absent for paymentIntentId=${event.paymentIntentId}")

        // 1. Advance aggregate state mutations
        val updatedPayment = payment.markSentForSettle()

        // 2. Link parent Authorization transaction context
        val txs = paymentTxPort.findByPaymentId(payment.paymentId.value)
        val authTx = txs.find { it.txType == com.dogancaglar.paymentservice.domain.model.ledger.JournalType.AUTHORIZATION }
        val authTxIdValue = authTx?.txId ?: TxId(0L)

        // 3. Setup transaction tracking metadata record
        val newTxId = TxId(idGeneratorPort.generateId())
        val amount = Amount.of(event.amountValue, Currency(event.currency))

        val captureTx = Tx.createCaptureTx(
            txId = newTxId,
            paymentId = payment.paymentId,
            paymentIntentId = paymentIntentId,
            authorizationTxId = authTxIdValue,
            acquirerReference = event.pspReference,
            amount = amount,
            status = PENDING
        )

        // 4. An auto-settled merchant has no acquirer: its capture confirmation and settlement are simulated here
        val merchant = merchantAccountRepository.findByCode(event.merchantAccount)
            ?: throw IllegalStateException("Merchant account absent for merchantAccount=${event.merchantAccount}")
        val outboxEvents = mutableListOf<OutboxEvent>()
        if (merchant.isAutoSettled) {
            logger.debug(
                "Merchant ${event.merchantAccount} is auto-settled: simulating capture confirmation and settlement."
            )
            val captureConfirmed = CaptureConfirmed(
                paymentIntentId = event.paymentIntentId,
                publicPaymentIntentId = event.publicPaymentIntentId,
                merchantAccount = event.merchantAccount,
                amountValue = event.amountValue,
                currency = event.currency
            )

            val captureConfirmedOutboxEvent = outboxEventFactoryPort.create(captureConfirmed)
            outboxEvents.add(captureConfirmedOutboxEvent,)
            val grossAmountValue = event.amountValue

            val feeAmountValue = simulatedPspFee(grossAmountValue)
            val netCashAmountValue = grossAmountValue - feeAmountValue

            val settlementLineEvent = SettlementReceived(
                paymentIntentId = event.paymentIntentId,
                publicPaymentIntentId = event.publicPaymentIntentId,
                merchantAccount = event.merchantAccount,
                grossAmountValue = grossAmountValue,
                pspFeeAmountValue = feeAmountValue,
                netCashAmountValue = netCashAmountValue,
                currency = event.currency
            )
            val settlementLineOutboxEvent = outboxEventFactoryPort.create(settlementLineEvent)

            outboxEvents.add(settlementLineOutboxEvent,)
        }

        // 5. Commit atomic units through outbound database gateways
        logger.debug(
            "Atomically persisting pending state modifications and transaction outbox event for track ref=${event.pspReference}"
        )
        centralDbTransactionalFacadePort.recordPaymentOperationInLedger(
            updatedPayment,
            captureTx,
            emptyList(),
            outboxEvents
        )
    }

    /**
     * The PSP fee the simulated settlement reports, like a blended EU card price: [SIMULATED_PSP_FEE_BPS] of the gross
     * (rounded down to the cent) plus [SIMULATED_PSP_FEE_FIXED]. A real PSP reports its own fee; we only book it.
     * E.g. 3000 cents: 3000 × 150 / 10,000 + 25 = 45 + 25 = 70.
     */
    private fun simulatedPspFee(grossAmountValue: Long): Long =
        grossAmountValue * SIMULATED_PSP_FEE_BPS / 10_000 + SIMULATED_PSP_FEE_FIXED

    private companion object {
        const val SIMULATED_PSP_FEE_BPS = 150L // 1.5%
        const val SIMULATED_PSP_FEE_FIXED = 25L // €0.25 in cents
    }
}
