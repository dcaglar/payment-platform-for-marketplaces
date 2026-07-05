package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka.consumers

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.kafka.metadata.CONSUMER_GROUPS
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.common.logging.EventLogContext
import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.application.util.toPublicPaymentIntentId
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.ledger.TxStatus
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.ports.inbound.usecases.RecordInternalTransferSubmissionUseCase
import com.dogancaglar.paymentservice.ports.outbound.EventDeduplicationPort
import com.dogancaglar.paymentservice.ports.outbound.PaymentRepository
import com.dogancaglar.paymentservice.ports.outbound.PaymentTxPort
import com.dogancaglar.paymentservice.ports.outbound.AccountDirectoryPort
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * GrossCaptureAllocationConsumer
 *
 * Listens for finalized JournalEntriesRecorded events and handles the critical task of draining
 * the transient MERCHANT_GROSS_CAPTURE_SUSPENSE pool down to exactly €0.
 * Routes funds to direct merchant revenue or splits them across sub-sellers and commissions.
 */
@Component
class GrossCaptureAllocationConsumer(
    private val paymentRepository: PaymentRepository,
    private val paymentTxPort: PaymentTxPort,
    private val accountDirectory: AccountDirectoryPort,
    private val dedupe: EventDeduplicationPort,
    private val objectMapper: ObjectMapper,
    private val recordInternalTransferSubmissionUseCase: RecordInternalTransferSubmissionUseCase
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [Topics.JOURNAL_ENTRIES_RECORDED],
        containerFactory = CONSUMER_GROUPS.WEBHOOK_CAPTURE_CONFIRMED_PROCESSOR + "-factory",
        groupId = CONSUMER_GROUPS.WEBHOOK_CAPTURE_CONFIRMED_PROCESSOR
    )
    fun onLedgerEntriesRecorded(
        record: ConsumerRecord<String, EventEnvelope<JournalEntriesRecorded>>
    ) {
        val envelope = record.value() as EventEnvelope<JournalEntriesRecorded>
        EventLogContext.with(envelope) {
            val eventId = envelope.data.deterministicEventId()
            if (dedupe.exists(eventId)) {
                logger.warn("⚠️ Event is processed already, skipping eventId=$eventId")
                return@with
            }

            val event = envelope.data
            logger.debug("🎬 Initiating ledger allocation clearing loop for paymentIntentId: ${event.publicPaymentIntentId}")

            try {
                // 1. Verify a successful CAPTURE journal entry exists in this ledger batch
                val captureEntry = event.ledgerEntries.find { it.journalType == JournalType.CAPTURE }
                if (captureEntry == null) {
                    logger.debug("No CAPTURE journal entry found in this JournalEntriesRecorded. No clearing allocation required.")
                    dedupe.markProcessed(eventId, 3600)
                    logger.info("Gross capture allocation consumer executed successfully for paymentIntentId=${event.publicPaymentIntentId}")
                    return@with
                }

                val rawPaymentIntentId = event.paymentIntentId.trim()
                val paymentIntentIdValue = rawPaymentIntentId.toLongOrNull() ?: 0L
                val paymentIntentId = PaymentIntentId(paymentIntentIdValue)
                val payment = paymentRepository.findByPaymentIntentId(paymentIntentId)
                if (payment == null) {
                    logger.error("🛑 POISON PILL DETECTED: Payment data entity not found for paymentIntentId='$rawPaymentIntentId'. This usually happens if the database was wiped but Kafka retained old data. Skipping event to unblock partition.")
                    dedupe.markProcessed(eventId, 3600)
                    return@with
                }

                val txs = paymentTxPort.findByPaymentId(payment.paymentId.value)
                val captureTx = txs.find { it.txType == JournalType.CAPTURE && it.status == TxStatus.SUCCESS }
                    ?: throw IllegalStateException("Successful CaptureTx record missing for paymentId=${payment.paymentId.value}")

                // 1. Resolve Global Platform Accounts

                        //
                val masterAccountCode = "${payment.merchantAccount}.${captureTx.amount.currency.currencyCode}"
                val grossSuspenseAccount = accountDirectory.getAccountProfile(AccountType.MERCHANT_GROSS_CAPTURE_SUSPENSE, masterAccountCode)
                val platformEscrowAccount = accountDirectory.getAccountProfile(AccountType.PLATFORM_COMMISSION_ESCROW, masterAccountCode)

                // Fixed infrastructure fee Mor-DC charges for processing this tx (e.g., €0.50)
                val morDcPlatformFee = Amount.of(50, captureTx.amount.currency)

                // === PATH A: Direct Merchant Payment (No Splits) ===
                if (payment.splits.isEmpty()) {
                    logger.info("🎯 Direct Sale context identified. Moving 100% of gross funds to merchant direct revenue folder.")

                    val merchantDirectRevenueAccount = accountDirectory.getAccountProfile(
                        AccountType.MARKETPLACE_DIRECT_REVENUE_BALANCE_ACCOUNT,
                        masterAccountCode
                    )
                    // A1. Move 100% of funds from suspense to direct merchant revenue
                    recordInternalTransferSubmissionUseCase.recordSubmission(
                        paymentId = payment.paymentId,
                        paymentIntentId = paymentIntentId,
                        paymentMerchantAccountId = payment.merchantAccount,
                        captureTxId = captureTx.txId,
                        sourceAccount = grossSuspenseAccount.accountCode,
                        targetAccount = merchantDirectRevenueAccount.accountCode,
                        transferAmount = captureTx.amount,
                        journalType = JournalType.INTERNAL_TRANSFER
                    )


                    // A2. Charge Mor-DC's infrastructure processing fee from direct revenue
                    recordInternalTransferSubmissionUseCase.recordSubmission(
                        paymentId = payment.paymentId,
                        paymentIntentId = paymentIntentId,
                        paymentMerchantAccountId = payment.merchantAccount,
                        captureTxId = captureTx.txId,
                        sourceAccount = merchantDirectRevenueAccount.accountCode, targetAccount = platformEscrowAccount.accountCode,
                        journalType = JournalType.COMMISSION_FEE, transferAmount = morDcPlatformFee
                    )

                    logger.info("💾 Suspense account cleanly cleared. Staged 100% allocation to direct revenue for merchant: ${payment.merchantAccount}")
                    dedupe.markProcessed(eventId, 3600)
                    return@with
                }

                // === PATH B: Marketplace Split Payment ===
                logger.info("🌿 Marketplace multi-party transaction identified. Executing clearing transfers for ${payment.splits.size} split definitions.")
                // === PATH B: Marketplace Split Payment ===
                // B1. Distribute the exact split allocations explicitly mapped by the paymetnsplit payload
                payment.splits.forEach { split ->
                    val targetAccountCode = "${split.account}.${split.amount.currency.currencyCode}"
                    recordInternalTransferSubmissionUseCase.recordSubmission(
                        paymentId = payment.paymentId,
                        paymentIntentId = paymentIntentId,
                        paymentMerchantAccountId = payment.merchantAccount,
                        captureTxId = captureTx.txId,
                        sourceAccount = grossSuspenseAccount.accountCode,
                        targetAccount = targetAccountCode,
                        transferAmount = split.amount,
                        journalType = JournalType.INTERNAL_TRANSFER
                    )
                }

                // B2. Charge Mor-DC's infrastructure fee straight from the operator's revenue share profile account
                val operatorCommissionAccount = accountDirectory.getAccountProfile(AccountType.MARKETPLACE_COMMISSION_REVENUE_BALANCE_ACCOUNT, masterAccountCode)

                recordInternalTransferSubmissionUseCase.recordSubmission(
                    paymentId = payment.paymentId, paymentIntentId = paymentIntentId,
                    paymentMerchantAccountId = payment.merchantAccount,
                    captureTxId = captureTx.txId,
                    sourceAccount = operatorCommissionAccount.accountCode, targetAccount = platformEscrowAccount.accountCode,
                    journalType = JournalType.COMMISSION_FEE, transferAmount = morDcPlatformFee
                )

                logger.info("💾 Suspense account cleanly cleared. Staged split ledger allocations across all ${payment.splits.size} distribution paths.")
                dedupe.markProcessed(eventId, 3600)
                logger.info("Gross capture allocation consumer executed successfully for paymentIntentId=${event.publicPaymentIntentId}")

            } catch (e: Exception) {
                logger.error("❌ Failed to clean and allocate gross capture suspense for paymentIntentId: ${event.publicPaymentIntentId}", e)
                throw e
            }
        }
    }
}