package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.common.time.Utc
import com.dogancaglar.common.logging.EventLogContext

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.Account
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry

import com.dogancaglar.paymentservice.ports.outbound.AccountDirectoryPort
import com.dogancaglar.paymentservice.ports.outbound.CentralDbTransactionalFacadePort
import com.dogancaglar.paymentservice.ports.outbound.PaymentTxPort
import com.dogancaglar.paymentservice.ports.outbound.IdGeneratorPort
import org.slf4j.LoggerFactory

import com.dogancaglar.paymentservice.domain.model.payment.Payment
import com.dogancaglar.paymentservice.domain.model.payment.PaymentStatus
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.ports.outbound.PaymentRepository
import com.dogancaglar.common.event.EventEnvelopeFactory
import com.dogancaglar.common.id.PublicIdFactory
import com.dogancaglar.paymentservice.application.events.CaptureConfirmed
import com.dogancaglar.paymentservice.application.events.CaptureRequested
import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.application.events.PaymentAuthorized
import com.dogancaglar.paymentservice.application.events.SettlementReceived
import com.dogancaglar.paymentservice.application.util.LedgerDomainEventEntityMapper
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.ledger.SettleStatus
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.ledger.Tx.CaptureTx
import com.dogancaglar.paymentservice.domain.model.ledger.TxStatus.PENDING
import com.dogancaglar.paymentservice.domain.model.ledger.TxStatus.SUCCESS
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentStatus.SENT_FOR_SETTLE
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import com.dogancaglar.paymentservice.ports.inbound.usecases.ProcessPspResultUseCase
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import com.dogancaglar.paymentservice.ports.outbound.SerializationPort
import com.dogancaglar.paymentservice.ports.outbound.TransferRepository

open class ProcessPspResultProcessingService(
    private val centralDbTransactionalFacadePort: CentralDbTransactionalFacadePort,
    private val accountDirectory: AccountDirectoryPort,
    private val paymentTxPort: PaymentTxPort,
    private val idGeneratorPort: IdGeneratorPort,
    private val paymentRepository: PaymentRepository,
    private val transferRepository: TransferRepository,
    private val serializationPort: SerializationPort,
    private val outboxEventFactoryPort: OutboxEventFactoryPort
) : ProcessPspResultUseCase{
    //todo make sure PspResultConsumer uses idemptotent state update sqls
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun processAuthorized(event: PaymentAuthorized) {
        val amount = Amount.of(event.totalAmountValue, Currency(event.currency))
        val authReceivable = Account.fromProfile(
            accountDirectory.getAccountProfile(AccountType.AUTH_RECEIVABLE, "GLOBAL.${event.currency}")
        )
        val authLiability = Account.fromProfile(
            accountDirectory.getAccountProfile(AccountType.AUTH_LIABILITY, "GLOBAL.${event.currency}")
        )

        val paymentIdValue = idGeneratorPort.generateId()
        val txIdValue = idGeneratorPort.generateId()

        // 1. Generate Payment main record
        val splits = event.splits.map { it.toDomain() }
        val payment = Payment.initializeFromAuthEvent(
            paymentId = PaymentId(paymentIdValue),
            paymentIntentId = PaymentIntentId(event.paymentIntentId.toLongOrNull() ?: 0L),
            buyerId = BuyerId(event.buyerId),
            merchantAccount = event.merchantAccountId,
            processingModel = ProcessingModel.valueOf(event.processingModel),
            totalAmount = amount,
            splits = splits
        )

        // 2. Generate AuthorizationTx(representsing )
        val transaction = Tx.createAuthTx(
            txId = TxId(txIdValue),
            paymentId = PaymentId(paymentIdValue),
            paymentIntentId = PaymentIntentId(event.paymentIntentId.toLongOrNull() ?: 0L),
            acquirerReference = "",
            amount = amount,
            status = SUCCESS
        )

        // 3. Generate JournalEntries
        val journalEntries = JournalEntry.authHold(
            globalJournalEntryId = idGeneratorPort.generateId(),
            paymentId = PaymentId(paymentIdValue),
            txId = TxId(txIdValue),
            journalIdentifier = event.paymentIntentId,
            authorizedAmount = amount,
            authReceivable = authReceivable,
            authLiability = authLiability
        )

        //4 default system should behave as if manual capture is received ad nd submitted, so wwe will simply creete outbox event with payload EventEnvelope<CaptureRequested>
        //and pass also that outboxevent in side saveatomicallym ethod
        val captureRequested = CaptureRequested(
            paymentIntentId = event.paymentIntentId,
            publicPaymentIntentId = event.publicPaymentIntentId,
            merchantAccountId = event.merchantAccountId,
            amountValue = event.totalAmountValue,
            currency = event.currency
        )

        val captureOutboxEvent = outboxEventFactoryPort.create(captureRequested)

        // 5. Emit an OutboxEvent containing the raw JournalEntries
        val now = Utc.nowInstant()

        val deterministicBatchId = "${transaction.txType.name}:${event.publicPaymentIntentId}:${transaction.txId.value}"
        val ledgerEvent = JournalEntriesRecorded.from(
            cmd = event,
            batchId = deterministicBatchId,
            entries = journalEntries.map { LedgerDomainEventEntityMapper.toLedgerEntryEventData(it) },
            customPartitionKey = event.merchantAccountId,
            now = now
        )
        val ledgerOutboxEvent = outboxEventFactoryPort.create(ledgerEvent)

        // 6. Persist all
        centralDbTransactionalFacadePort.recordPaymentOperationInLedger(
            payment = payment,
            tx = transaction,
            journalEntries = journalEntries,
            outboxEvents = listOf(captureOutboxEvent, ledgerOutboxEvent)
        )
    }

    override fun processCaptureConfirmed(event: CaptureConfirmed) {
        val paymentIntentId = PaymentIntentId(PublicIdFactory.toInternalId(event.publicPaymentIntentId))
        val payment = paymentRepository.findByPaymentIntentId(paymentIntentId)
            ?: throw IllegalStateException("Payment not found for paymentIntentId=\${event.publicPaymentIntentId}")

        // Invariant check: ensure it was sent for settle
        require(payment.status == SENT_FOR_SETTLE) {
            "Payment must be in SENT_FOR_SETTLE status, but was \${payment.status}"
        }

        // Apply capture to advance state to CAPTURED
        val amount = Amount.of(event.amountValue, Currency(event.currency))
        val capturedPayment = payment.applyCapture(amount)

        // Find pending Capture Tx and mark success
        val txs = paymentTxPort.findByPaymentId(payment.paymentId.value)
        val captureTx = txs.find { it.txType == com.dogancaglar.paymentservice.domain.model.ledger.JournalType.CAPTURE && it.status == PENDING }
            ?: throw IllegalStateException("Pending CaptureTx not found for paymentId=\${payment.paymentId.value}")

        val updatedTx = (captureTx as CaptureTx).copy(
            status = SUCCESS
        )
        // Commit gross ledger distributions
        val merchantGrossPool = Account.fromProfile(accountDirectory.getAccountProfile(AccountType.MERCHANT_GROSS_CAPTURE_SUSPENSE, "${event.merchantAccountId}.${event.currency}"))
        val authReceivable = Account.fromProfile(accountDirectory.getAccountProfile(AccountType.AUTH_RECEIVABLE, "GLOBAL.${event.currency}"))
        val authLiability = Account.fromProfile(accountDirectory.getAccountProfile(AccountType.AUTH_LIABILITY, "GLOBAL.${event.currency}"))
        val pspReceivable = Account.fromProfile(accountDirectory.getAccountProfile(AccountType.PSP_RECEIVABLES, "GLOBAL.${event.currency}"))

        val journalEntries = JournalEntry.captureGrossAsset(
            globalJournalEntryId = idGeneratorPort.generateId(),
            paymentId = payment.paymentId,
            txId = captureTx.txId,
            journalIdentifier = event.publicPaymentIntentId,
            capturedAmount = amount,
            authReceivable = authReceivable,
            authLiability = authLiability,
            merchantGrossPool = merchantGrossPool,
            pspReceivable = pspReceivable
        )

        // Emit an OutboxEvent containing the raw JournalEntries
        // This decouples marketplace split logic from basic capture processing
        val now = Utc.nowInstant()
        val deterministicBatchId = "${captureTx.txType.name}:${event.publicPaymentIntentId}:${captureTx.txId.value}:${captureTx.status.name}"
        val ledgerEvent = JournalEntriesRecorded.from(
            cmd = event,
            batchId = deterministicBatchId,
            entries = journalEntries.map { LedgerDomainEventEntityMapper.toLedgerEntryEventData(it) },
            customPartitionKey = event.merchantAccountId,
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


    override fun processInternalTransferCommand(event: com.dogancaglar.paymentservice.application.events.InternalTransferCommand) {
        val amount = Amount.of(event.amountValue, Currency(event.currency))

        // 1. Resolve accounts directly from the event (pre-resolved by the Consumer)
        val sourceAccount = Account.fromProfile(accountDirectory.getAccountByCode(event.sourceAccount))
        val targetAccount = Account.fromProfile(accountDirectory.getAccountByCode(event.targetAccount))

        val paymentIntentId = PaymentIntentId(event.paymentIntentId.toLongOrNull() ?: 0L)
        val payment = paymentRepository.findByPaymentIntentId(paymentIntentId)
            ?: throw IllegalStateException("Payment not found for paymentIntentId=${event.paymentIntentId}")
        // 2. Load InternalTransfer and Tx
        val transferId = com.dogancaglar.paymentservice.domain.model.vo.InternalTransferId(event.transferId)
        val transfer = transferRepository.findById(transferId)
            ?: throw IllegalStateException("InternalTransfer not found for transferId=${event.transferId}")

        val updatedTransfer = transfer.markTransferred()

        val publicTransferId = PublicIdFactory.publicInternalTransferId(transferId.value)
        val journalIdentifier = "${event.publicPaymentIntentId}-${event.sourceAccount}+${event.targetAccount}"

        //  Polymorphic invocation selects exact journal factory profile structures cleanly!
        val journalEntries =
            when (JournalType.valueOf(event.journalType)) {
            JournalType.COMMISSION_FEE -> JournalEntry.commissionFeeRegistered(
                globalJournalEntryId = idGeneratorPort.generateId(),
                paymentId = payment.paymentId,
                journalIdentifier = journalIdentifier,
                commissionFee = amount,
                commissionEscrowAccount = targetAccount, // Maps explicitly based on design
                merchantGrossPool = sourceAccount,
            )

            JournalType.REVENUE_RECOGNITION -> JournalEntry.recognizePlatformRevenue(
                globalJournalEntryId = idGeneratorPort.generateId(),
                recognitionIdentifier = journalIdentifier,
                maturedFeeAmount = amount,
                commissionEscrowAccount = sourceAccount,
                platformOperationalRevenue = targetAccount
            )

            JournalType.INTERNAL_TRANSFER -> JournalEntry.internalTransfer(
                globalJournalEntryId = idGeneratorPort.generateId(),
                paymentId = payment.paymentId,
                journalIdentifier = journalIdentifier,
                amount = amount,
                sourceAccount = sourceAccount,
                targetAccount = targetAccount
            )

                else -> {throw IllegalArgumentException("Unexped journal type, journal type: ${event.journalType}")
                }
            }
        val now = Utc.nowInstant()
        val deterministicBatchId =  "${JournalType.INTERNAL_TRANSFER}:${event.publicPaymentIntentId}:${publicTransferId}:${event.sourceAccount}:${event.targetAccount}"
        val ledgerEvent = JournalEntriesRecorded.from(
            cmd = event,
            batchId = deterministicBatchId ,
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

    override fun processSettlementLineReconciled(event: SettlementReceived) {// well this SettlementReceived cant be linked yet to our internal transactions
        val paymentIntentId = PaymentIntentId(PublicIdFactory.toInternalId(event.publicPaymentIntentId))

        // 1. Fetch complete domain models from read-write ports
        val payment = paymentRepository.findByPaymentIntentId(paymentIntentId)
            ?: throw IllegalStateException("Payment target absent for settlement mapping context paymentIntentId=${event.publicPaymentIntentId}")

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

        val platformCash = Account.fromProfile(accountDirectory.getAccountProfile(AccountType.PLATFORM_CASH, "GLOBAL.${event.currency}"))
        val pspReceivable = Account.fromProfile(accountDirectory.getAccountProfile(AccountType.PSP_RECEIVABLES, "GLOBAL.${event.currency}"))
        val pspFeeExpense = Account.fromProfile(accountDirectory.getAccountProfile(AccountType.PSP_FEE_EXPENSE, "GLOBAL.${event.currency}"))

        val netCashAmount = Amount.of(event.netCashAmountValue, Currency(event.currency))
        val feeAmount = Amount.of(event.pspFeeAmountValue, Currency(event.currency))

        val settlementJournals = JournalEntry.settlementLineItem(
            globalJournalEntryId = idGeneratorPort.generateId(),
            paymentId = payment.paymentId,
            settlementTxId = settlementTxId,
            journalIdentifier = journalIdentifier,
            grossAmount = actualGrossAmount,
            netCashAmount = netCashAmount,
            pspFeeAmount = feeAmount,
            platformCash = platformCash,
            pspReceivable = pspReceivable,
            pspFeeExpense = pspFeeExpense
        )

        val settlementTxRecord = Tx.createSettleTx(
            txId = settlementTxId,
            paymentId = payment.paymentId,
            paymentIntentId = paymentIntentId,
            captureTxId = updatedCaptureTx.txId,
            acquirerBatchReference = journalIdentifier,
            grossAmount = actualGrossAmount,
            feeAmount = feeAmount,
            netCashAmount = netCashAmount,
            originalCaptureAmount = updatedCaptureTx.amount
        )

        val now = Utc.nowInstant()
        val deterministicBatchId = "${settlementTxRecord.txType.name}:${event.publicPaymentIntentId}:${settlementTxRecord.txId.value}:${settlementTxRecord.status.name}"
        val ledgerEvent = JournalEntriesRecorded.from(
            cmd = event,
            batchId = deterministicBatchId,
            entries = settlementJournals.map { LedgerDomainEventEntityMapper.toLedgerEntryEventData(it) },
            customPartitionKey = event.merchantAccountId,
            now = now
        )

        val ledgerOutboxEvent = outboxEventFactoryPort.create(ledgerEvent)
        // 5. Persist the fully validated units safely through outbound ports
        logger.debug("Committing balanced reconciliation tracking state indices atomically for paymentId=${payment.paymentId.value}")
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
