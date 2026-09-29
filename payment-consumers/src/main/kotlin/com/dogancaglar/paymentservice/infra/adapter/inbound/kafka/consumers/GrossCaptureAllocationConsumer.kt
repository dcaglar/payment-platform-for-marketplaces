package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka.consumers

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.kafka.metadata.CONSUMER_GROUPS
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.common.logging.EventLogContext
import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.application.util.toPublicPaymentIntentId
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
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
 * the transient CAPTURE_SUSPENSE pool down to exactly €0.
 * Routes funds to the merchant's direct payable account or splits them across sub-sellers and commissions.
 */
@Component
class GrossCaptureAllocationConsumer(
    private val paymentRepository: PaymentRepository,
    private val accountDirectory: AccountDirectoryPort,
    private val dedupe: EventDeduplicationPort,
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
            if (dedupe.exists(CONSUMER_GROUPS.WEBHOOK_CAPTURE_CONFIRMED_PROCESSOR, eventId)) {
                logger.warn("⚠️ Event is processed already, skipping eventId=$eventId")
                return@with
            }

            val event = envelope.data
            logger.debug("🎬 Initiating ledger allocation clearing loop for paymentIntentId: ${event.publicPaymentIntentId}")

            try {
                // 1. Verify a successful CAPTURE journal entry exists in this ledger batch
                val captureEntry = event.ledgerEntries.find { it.journalType == JournalType.CAPTURE }
                if (captureEntry == null) {
                    logger.debug("No CAPTURE journal entry found. No clearing allocation required.")
                    dedupe.markProcessed(CONSUMER_GROUPS.WEBHOOK_CAPTURE_CONFIRMED_PROCESSOR, eventId, 3600)
                    return@with
                }
                val rawPaymentIntentId = event.paymentIntentId.trim()
                val paymentIntentIdValue = rawPaymentIntentId.toLongOrNull() ?: 0L
                val paymentIntentId = PaymentIntentId(paymentIntentIdValue)
                val payment = paymentRepository.findByPaymentIntentId(paymentIntentId)
                if (payment == null) {
                    logger.error("🛑 POISON PILL DETECTED: Payment data entity not found for paymentIntentId='$rawPaymentIntentId'.")
                    dedupe.markProcessed(CONSUMER_GROUPS.WEBHOOK_CAPTURE_CONFIRMED_PROCESSOR, eventId, 3600)
                    return@with
                }
                // 1. Resolve Global Platform Accounts
                val currency = Currency(captureEntry.postings.first().currency)
                val merchantAccount = payment.merchantAccount
                val grossSuspenseAccount = accountDirectory.getAccountProfile(AccountType.CAPTURE_SUSPENSE, merchantAccount, currency)
                val platformFeeReserveAccount = accountDirectory.getAccountProfile(AccountType.PLATFORM_FEE_RESERVE, merchantAccount, currency)

                // Fixed infrastructure fee Mor-DC charges for processing this tx (e.g., €0.50)
                val morDcPlatformFee = Amount.of(50,currency)

                // === PATH A: Direct Merchant Payment (No Splits) ===
                if (payment.splits.isEmpty()) {
                    logger.info("🎯 Direct Sale context identified. Moving 100% of gross funds to merchant direct payable account.")
                    val merchantDirectPayableAccount = accountDirectory.getAccountProfile(
                        AccountType.MERCHANT_DIRECT_PAYABLE,
                        merchantAccount,
                        currency
                    )
                    // A1. Move 100% of funds from suspense to the merchant's direct payable account
                    recordInternalTransferSubmissionUseCase.recordSubmission(
                        paymentId = payment.paymentId,
                        paymentIntentId = paymentIntentId,
                        paymentMerchantAccountId = payment.merchantAccount,
                        sourceAccount = grossSuspenseAccount.accountCode,
                        targetAccount = merchantDirectPayableAccount.accountCode,
                        transferAmount = Amount.of(captureEntry.postings.first().amount,currency),
                        journalType = JournalType.INTERNAL_TRANSFER,
                        reason = "DIRECT_MERCHANT_REVENUE_ALLOCATION"
                    )


                    // A2. Charge Mor-DC's infrastructure processing fee from the merchant's direct payable account
                    recordInternalTransferSubmissionUseCase.recordSubmission(
                        paymentId = payment.paymentId,
                        paymentIntentId = paymentIntentId,
                        paymentMerchantAccountId = payment.merchantAccount,
                        sourceAccount = merchantDirectPayableAccount.accountCode,
                        targetAccount = platformFeeReserveAccount.accountCode,
                        transferAmount = morDcPlatformFee,
                        journalType = JournalType.COMMISSION_FEE,
                        reason = "MOR_DC_INFRASTRUCTURE_PROCESSING_FEE"
                    )

                    logger.info("💾 Suspense account cleanly cleared. Staged 100% allocation to direct payable for merchant: ${payment.merchantAccount}")
                    dedupe.markProcessed(CONSUMER_GROUPS.WEBHOOK_CAPTURE_CONFIRMED_PROCESSOR, eventId, 3600)
                    return@with
                }

                // === PATH B: Marketplace Split Payment ===
                logger.info("🌿 Marketplace multi-party transaction identified. Executing clearing transfers for ${payment.splits.size} split definitions.")
                // === PATH B: Marketplace Split Payment ===
                // The operator's commission account. Commission splits are credited here (B1) and
                // Mor-DC's fee is debited from here (B2), so its balance is the net amount owed to the operator.
                val operatorCommissionAccount = accountDirectory.getAccountProfile(AccountType.MERCHANT_COMMISSION_PAYABLE, merchantAccount, currency)

                // B1. Distribute the exact split allocations explicitly mapped by the paymetnsplit payload
                payment.splits.forEach { split ->
                    val targetAccountCode: String
                    val reason: String
                    if (split.accountType == AccountType.MERCHANT_COMMISSION_PAYABLE) {
                        // Commission split: split.account holds the merchant id, so resolve the commission account instead
                        targetAccountCode = operatorCommissionAccount.accountCode
                        reason = "MARKETPLACE_OPERATOR_COMMISSION_DISTRIBUTION"
                    } else if (split.accountType == AccountType.SELLER_PAYABLE) {
                        // Seller split: split.account holds the seller id. The seller must belong to this merchant.
                        val sellerAccount = accountDirectory.getSubEntityAccountProfile(
                            AccountType.SELLER_PAYABLE,
                            merchantAccount,
                            split.account,
                            split.amount.currency
                        )
                        targetAccountCode = sellerAccount.accountCode
                        reason = "MARKETPLACE_SELLER_SPLIT_DISTRIBUTION"
                    } else {
                        throw IllegalArgumentException("Unsupported split account type: ${split.accountType} for account=${split.account}")
                    }
                    recordInternalTransferSubmissionUseCase.recordSubmission(
                        paymentId = payment.paymentId,
                        paymentIntentId = paymentIntentId,
                        paymentMerchantAccountId = payment.merchantAccount,
                        sourceAccount = grossSuspenseAccount.accountCode,
                        targetAccount = targetAccountCode,
                        transferAmount = split.amount,
                        journalType = JournalType.INTERNAL_TRANSFER,
                        reason = reason
                    )
                }

                // B2. Charge Mor-DC's infrastructure fee straight from the operator's commission payable account
                recordInternalTransferSubmissionUseCase.recordSubmission(
                    paymentId = payment.paymentId,
                    paymentIntentId = paymentIntentId,
                    paymentMerchantAccountId = payment.merchantAccount,
                    sourceAccount = operatorCommissionAccount.accountCode,
                    targetAccount = platformFeeReserveAccount.accountCode,
                    transferAmount = morDcPlatformFee,
                    journalType = JournalType.COMMISSION_FEE,
                    reason = "MOR_DC_MARKETPLACE_OPERATOR_PROCESSING_FEE"
                )

                logger.info("💾 Suspense account cleanly cleared. Staged split ledger allocations across all ${payment.splits.size} distribution paths.")
                dedupe.markProcessed(CONSUMER_GROUPS.WEBHOOK_CAPTURE_CONFIRMED_PROCESSOR, eventId, 3600)
                logger.info("Gross capture allocation consumer executed successfully for paymentIntentId=${event.publicPaymentIntentId}")

            } catch (e: Exception) {
                logger.error("❌ Failed to clean and allocate gross capture suspense for paymentIntentId: ${event.publicPaymentIntentId}", e)
                throw e
            }
        }
    }
}