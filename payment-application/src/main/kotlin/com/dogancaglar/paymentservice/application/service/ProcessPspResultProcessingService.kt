package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.common.id.PublicIdFactory
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.application.events.AuthorizationDetails
import com.dogancaglar.paymentservice.application.events.CaptureConfirmed
import com.dogancaglar.paymentservice.application.events.CaptureRequested
import com.dogancaglar.paymentservice.application.events.InternalTransferCommand
import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.application.events.PaymentAuthorized
import com.dogancaglar.paymentservice.application.events.SettlementReceived
import com.dogancaglar.paymentservice.application.util.LedgerDomainEventEntityMapper
import com.dogancaglar.paymentservice.domain.exception.AccountDomainException
import com.dogancaglar.paymentservice.domain.exception.InternalTransferDomainException
import com.dogancaglar.paymentservice.domain.exception.PaymentDomainException
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.ledger.Tx.CaptureTx
import com.dogancaglar.paymentservice.domain.model.ledger.TxStatus.PENDING
import com.dogancaglar.paymentservice.domain.model.ledger.TxStatus.SUCCESS
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.domain.model.payment.Payment
import com.dogancaglar.paymentservice.domain.model.payment.PaymentStatus.SENT_FOR_SETTLE
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.InternalTransferId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import com.dogancaglar.paymentservice.ports.inbound.usecases.ProcessPspResultUseCase
import com.dogancaglar.paymentservice.ports.outbound.AccountDirectoryPort
import com.dogancaglar.paymentservice.ports.outbound.CentralDbTransactionalFacadePort
import com.dogancaglar.paymentservice.ports.outbound.IdGeneratorPort
import com.dogancaglar.paymentservice.ports.outbound.MerchantAccountRepository
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import com.dogancaglar.paymentservice.ports.outbound.PaymentRepository
import com.dogancaglar.paymentservice.ports.outbound.PaymentTxPort
import com.dogancaglar.paymentservice.ports.outbound.TransferRepository
import org.slf4j.LoggerFactory

open class ProcessPspResultProcessingService(
    private val centralDbTransactionalFacadePort: CentralDbTransactionalFacadePort,
    private val accountDirectory: AccountDirectoryPort,
    private val paymentTxPort: PaymentTxPort,
    private val idGeneratorPort: IdGeneratorPort,
    private val paymentRepository: PaymentRepository,
    private val transferRepository: TransferRepository,
    private val outboxEventFactoryPort: OutboxEventFactoryPort,
    private val merchantAccountRepository: MerchantAccountRepository
) : ProcessPspResultUseCase {
    // todo make sure PspResultConsumer uses idemptotent state update sqls
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun processAuthorized(event: PaymentAuthorized) {
        val amount = Amount.of(event.totalAmountValue, Currency(event.currency))
        // The authorization hold is tracked per merchant: its "authorized, not yet captured" amount
        val authReceivable = LedgerAccount.fromProfile(
            accountDirectory.getAccountProfile(
                LedgerAccountType.AUTH_RECEIVABLE,
                event.merchantAccount,
                Currency(event.currency)
            )
        )
        val authLiability = LedgerAccount.fromProfile(
            accountDirectory.getAccountProfile(
                LedgerAccountType.AUTH_LIABILITY,
                event.merchantAccount,
                Currency(event.currency)
            )
        )

        val paymentIdValue = idGeneratorPort.generateId()
        val txIdValue = idGeneratorPort.generateId()

        // 1. Generate Payment main record
        val splits = event.splits.map { it.toDomain() }
        val payment = Payment.initializeFromAuthEvent(
            paymentId = PaymentId(paymentIdValue),
            paymentIntentId = PaymentIntentId(event.paymentIntentId.toLongOrNull() ?: 0L),
            buyerId = BuyerId(event.buyerId),
            merchantAccount = event.merchantAccount,
            processingModel = ProcessingModel.valueOf(event.processingModel),
            totalAmount = amount,
            splits = splits
        )

        // 2. Generate AuthorizationTx(representsing )
        val transaction = Tx.createAuthTx(
            txId = TxId(txIdValue),
            payment = payment,
            acquirerReference = event.pspReference
        )

        // 3. Generate JournalEntries
        val journalEntries = JournalEntry.authHold(
            globalJournalEntryId = idGeneratorPort.generateId(),
            authTx = transaction,
            journalIdentifier = event.paymentIntentId,
            authReceivable = authReceivable,
            authLiability = authLiability
        )

        // 4. An auto-captured merchant's payment is captured right away: CaptureRequested goes out with the ledger
        // event
        val merchant = merchantAccountRepository.findByCode(event.merchantAccount)
            ?: throw AccountDomainException.MerchantAccountNotFoundException("merchantAccount=${event.merchantAccount}")
        val outboxEvents = mutableListOf<OutboxEvent>()
        if (merchant.isAutoCaptured) {
            val captureRequested = CaptureRequested(
                paymentIntentId = event.paymentIntentId,
                publicPaymentIntentId = event.publicPaymentIntentId,
                merchantAccount = event.merchantAccount,
                amountValue = event.totalAmountValue,
                currency = event.currency
            )
            outboxEvents.add(outboxEventFactoryPort.create(captureRequested))
        }

        // 5. Emit an OutboxEvent containing the raw JournalEntries
        val now = Utc.nowInstant()

        val deterministicBatchId = "${transaction.txType.name}:${event.publicPaymentIntentId}:${transaction.txId.value}"
        val ledgerEvent = JournalEntriesRecorded.from(
            cmd = event,
            batchId = deterministicBatchId,
            entries = journalEntries.map { LedgerDomainEventEntityMapper.toLedgerEntryEventData(it) },
            customPartitionKey = event.merchantAccount,
            now = now,
            // the first ledger event of this payment also says who and what it is (read by the back office)
            authorization = AuthorizationDetails.from(event)
        )
        outboxEvents.add(outboxEventFactoryPort.create(ledgerEvent))

        // 6. Persist all (nothing is written when this intent already has a Payment: a replay)
        val recorded = centralDbTransactionalFacadePort.recordAuthorizationInLedger(
            payment = payment,
            tx = transaction,
            journalEntries = journalEntries,
            outboxEvents = outboxEvents
        )
        if (!recorded) {
            logger.debug("payment_authorized for {} was already recorded, nothing written", event.publicPaymentIntentId)
        }
    }

    override fun processCaptureConfirmed(event: CaptureConfirmed) {
        val paymentIntentId = PaymentIntentId(PublicIdFactory.toInternalId(event.publicPaymentIntentId))
        val payment = paymentRepository.findByPaymentIntentId(paymentIntentId)
            ?: throw PaymentDomainException.PaymentNotFoundException("paymentIntentId=${event.publicPaymentIntentId}")

        // Invariant check: ensure it was sent for settle
        require(payment.status == SENT_FOR_SETTLE) {
            "Payment must be in SENT_FOR_SETTLE status, but was ${payment.status}"
        }

        // Apply capture to advance state to CAPTURED
        val amount = Amount.of(event.amountValue, Currency(event.currency))
        val capturedPayment = payment.applyCapture(amount)

        // Find pending Capture Tx and mark success
        val txs = paymentTxPort.findByPaymentId(payment.paymentId.value)
        val captureTx = txs.find {
            it.txType == JournalType.CAPTURE && it.status == PENDING
        }
            ?: throw PaymentDomainException.CaptureTxNotFoundException(
                "No PENDING capture tx for paymentId=${payment.paymentId.value}"
            )

        val updatedTx = (captureTx as CaptureTx).copy(
            status = SUCCESS
        )
        // Commit gross ledger distributions
        val merchantGrossPool = LedgerAccount.fromProfile(
            accountDirectory.getAccountProfile(
                LedgerAccountType.CAPTURE_SUSPENSE,
                event.merchantAccount,
                Currency(event.currency)
            )
        )
        // Releases the merchant's authorization hold (booked per merchant in processAuthorized)
        val authReceivable = LedgerAccount.fromProfile(
            accountDirectory.getAccountProfile(
                LedgerAccountType.AUTH_RECEIVABLE,
                event.merchantAccount,
                Currency(event.currency)
            )
        )
        val authLiability = LedgerAccount.fromProfile(
            accountDirectory.getAccountProfile(
                LedgerAccountType.AUTH_LIABILITY,
                event.merchantAccount,
                Currency(event.currency)
            )
        )
        val pspReceivable = LedgerAccount.fromProfile(
            accountDirectory.getAccountProfile(LedgerAccountType.PSP_RECEIVABLE, "GLOBAL", Currency(event.currency))
        )

        val journalEntries = JournalEntry.captureGrossAsset(
            globalJournalEntryId = idGeneratorPort.generateId(),
            captureTx = updatedTx,
            // One journal per capture: keyed by the capture tx id, so multiple (partial/manual) captures
            // of one payment don't share an id, while a redelivery of the same capture still does.
            journalIdentifier = "${event.publicPaymentIntentId}-${captureTx.txId.value}",
            capturedAmount = amount,
            authReceivable = authReceivable,
            authLiability = authLiability,
            merchantGrossPool = merchantGrossPool,
            pspReceivable = pspReceivable
        )

        // Emit an OutboxEvent containing the raw JournalEntries
        // This decouples marketplace split logic from basic capture processing
        val now = Utc.nowInstant()
        val deterministicBatchId = "${captureTx.txType.name}:${event.publicPaymentIntentId}:" +
            "${captureTx.txId.value}:${captureTx.status.name}"
        val ledgerEvent = JournalEntriesRecorded.from(
            cmd = event,
            batchId = deterministicBatchId,
            entries = journalEntries.map { LedgerDomainEventEntityMapper.toLedgerEntryEventData(it) },
            customPartitionKey = event.merchantAccount,
            now = now
        )

        val outboxEvent = outboxEventFactoryPort.create(ledgerEvent)
        centralDbTransactionalFacadePort.recordPaymentOperationInLedger(
            payment = capturedPayment,
            tx = updatedTx,
            journalEntries = journalEntries,
            outboxEvents = listOf(outboxEvent)
        )
    }

    override fun processInternalTransferCommand(event: InternalTransferCommand) {
        val amount = Amount.of(event.amountValue, Currency(event.currency))

        // 1. Resolve accounts directly from the event (pre-resolved by the Consumer)
        val sourceAccount = LedgerAccount.fromProfile(accountDirectory.getAccountByCode(event.sourceAccount))
        val targetAccount = LedgerAccount.fromProfile(accountDirectory.getAccountByCode(event.targetAccount))

        val paymentIntentId = PaymentIntentId(event.paymentIntentId.toLongOrNull() ?: 0L)
        val payment = paymentRepository.findByPaymentIntentId(paymentIntentId)
            ?: throw PaymentDomainException.PaymentNotFoundException("paymentIntentId=${event.paymentIntentId}")
        // 2. Load InternalTransfer and Tx
        val transferId = InternalTransferId(event.transferId)
        val transfer = transferRepository.findById(transferId)
            ?: throw InternalTransferDomainException.TransferNotFoundException("transferId=${event.transferId}")

        val updatedTransfer = transfer.markTransferred()

        val publicTransferId = PublicIdFactory.publicInternalTransferId(transferId.value)
        // One journal per transfer: keyed by the transfer id (stable on redelivery, unique per transfer).
        // Source+target is not enough: two transfers on the same route in one payment would share the id.
        val journalIdentifier = "${event.publicPaymentIntentId}-$publicTransferId"

        //  Polymorphic invocation selects exact journal factory profile structures cleanly!
        val journalEntries =
            when (JournalType.valueOf(event.journalType)) {
                JournalType.COMMISSION_FEE -> JournalEntry.commissionFeeRegistered(
                    globalJournalEntryId = idGeneratorPort.generateId(),
                    paymentId = payment.paymentId,
                    journalIdentifier = journalIdentifier,
                    commissionFee = amount,
                    feeReserveAccount = targetAccount, // Maps explicitly based on design
                    merchantPayableAccount = sourceAccount,
                )

                JournalType.REVENUE_RECOGNITION -> JournalEntry.recognizePlatformRevenue(
                    globalJournalEntryId = idGeneratorPort.generateId(),
                    recognitionIdentifier = journalIdentifier,
                    maturedFeeAmount = amount,
                    feeReserveAccount = sourceAccount,
                    platformRevenue = targetAccount
                )

                JournalType.INTERNAL_TRANSFER -> JournalEntry.internalTransfer(
                    globalJournalEntryId = idGeneratorPort.generateId(),
                    paymentId = payment.paymentId,
                    journalIdentifier = journalIdentifier,
                    amount = amount,
                    sourceAccount = sourceAccount,
                    targetAccount = targetAccount
                )

                else -> {
                    throw IllegalArgumentException("Unexped journal type, journal type: ${event.journalType}")
                }
            }
        val now = Utc.nowInstant()
        val deterministicBatchId = "${JournalType.INTERNAL_TRANSFER}:${event.publicPaymentIntentId}:" +
            "$publicTransferId:${event.sourceAccount}:${event.targetAccount}"
        val ledgerEvent = JournalEntriesRecorded.from(
            cmd = event,
            batchId = deterministicBatchId,
            entries = journalEntries.map { LedgerDomainEventEntityMapper.toLedgerEntryEventData(it) },
            customPartitionKey = event.targetAccount,
            now = now
        )

        val outboxEvent = outboxEventFactoryPort.create(ledgerEvent)

        // 4. Persist
        centralDbTransactionalFacadePort.recordInternalTransferOperationInLedger(
            internalTransfer = updatedTransfer,
            journalEntries = journalEntries,
            outboxEvents = listOf(outboxEvent)
        )
    }

    // well this SettlementReceived cant be linked yet to our internal transactions
    override fun processSettlementLineReconciled(event: SettlementReceived) {
        val paymentIntentId = PaymentIntentId(PublicIdFactory.toInternalId(event.publicPaymentIntentId))

        // 1. Fetch complete domain models from read-write ports
        val payment = paymentRepository.findByPaymentIntentId(paymentIntentId)
            ?: throw PaymentDomainException.PaymentNotFoundException("paymentIntentId=${event.publicPaymentIntentId}")

        val txHistory = paymentTxPort.findByPaymentId(payment.paymentId.value)

        // 2. Filter out pure collections to feed into our aggregate root
        val captureTransactions = txHistory.filterIsInstance<Tx.CaptureTx>()

        val actualGrossAmount = Amount.of(event.grossAmountValue, Currency(event.currency))

        // 3. EXECUTE DOMAIN-CONTROLLED STATE TRANSITION
        // The aggregate owns finding the target line and processes the business rules
        val reconciliationResult = payment.reconcileCaptureSettlement(
            actualGrossAmount = actualGrossAmount,
            allCaptures = captureTransactions
        )

        val updatedPayment = reconciliationResult.payment
        val updatedCaptureTx = reconciliationResult.captureTx

        // 4. Formulate Ledger Accounting Structures (Unchanged)
        val settlementTxId = TxId(idGeneratorPort.generateId())
        val journalIdentifier = "SDR_RECON_LN_${settlementTxId.value}"

        val platformCash = LedgerAccount.fromProfile(
            accountDirectory.getAccountProfile(LedgerAccountType.PLATFORM_CASH, "GLOBAL", Currency(event.currency))
        )
        val pspReceivable = LedgerAccount.fromProfile(
            accountDirectory.getAccountProfile(LedgerAccountType.PSP_RECEIVABLE, "GLOBAL", Currency(event.currency))
        )
        val pspFeeExpense = LedgerAccount.fromProfile(
            accountDirectory.getAccountProfile(LedgerAccountType.PSP_FEE_EXPENSE, "GLOBAL", Currency(event.currency))
        )

        val netCashAmount = Amount.of(event.netCashAmountValue, Currency(event.currency))
        val feeAmount = Amount.of(event.pspFeeAmountValue, Currency(event.currency))

        val settlementTxRecord = Tx.createSettleTx(
            txId = settlementTxId,
            captureTx = updatedCaptureTx,
            acquirerBatchReference = journalIdentifier,
            grossAmount = actualGrossAmount
        )

        val settlementJournals = JournalEntry.settlementLineItem(
            globalJournalEntryId = idGeneratorPort.generateId(),
            settleTx = settlementTxRecord,
            journalIdentifier = journalIdentifier,
            netCashAmount = netCashAmount,
            pspFeeAmount = feeAmount,
            platformCash = platformCash,
            pspReceivable = pspReceivable,
            pspFeeExpense = pspFeeExpense
        )

        val now = Utc.nowInstant()
        val deterministicBatchId = "${settlementTxRecord.txType.name}:${event.publicPaymentIntentId}:" +
            "${settlementTxRecord.txId.value}:${settlementTxRecord.status.name}"
        val ledgerEvent = JournalEntriesRecorded.from(
            cmd = event,
            batchId = deterministicBatchId,
            entries = settlementJournals.map { LedgerDomainEventEntityMapper.toLedgerEntryEventData(it) },
            customPartitionKey = event.merchantAccount,
            now = now
        )

        val ledgerOutboxEvent = outboxEventFactoryPort.create(ledgerEvent)
        // 5. Persist the fully validated units safely through outbound ports
        logger.debug(
            "Committing balanced reconciliation tracking state indices atomically for " +
                "paymentId=${payment.paymentId.value}"
        )
        centralDbTransactionalFacadePort.recordPaymentOperationInLedger(
            payment = updatedPayment,
            tx = settlementTxRecord,
            journalEntries = settlementJournals,
            outboxEvents = listOf(ledgerOutboxEvent)
        )

        // Save updated child status row to complete the lifecycle trace
        paymentTxPort.save(updatedCaptureTx)
    }
}
