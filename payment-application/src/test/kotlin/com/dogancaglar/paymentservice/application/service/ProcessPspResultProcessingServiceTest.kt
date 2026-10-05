package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.common.id.PublicIdFactory
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.application.events.CaptureConfirmed
import com.dogancaglar.paymentservice.application.events.InternalTransferCommand
import com.dogancaglar.paymentservice.domain.model.account.AccountStatus
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.payment.InternalTransfer
import com.dogancaglar.paymentservice.domain.model.payment.InternalTransferStatus
import com.dogancaglar.paymentservice.domain.model.payment.Payment
import com.dogancaglar.paymentservice.domain.model.payment.PaymentStatus
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.InternalTransferId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import com.dogancaglar.paymentservice.ports.outbound.AccountDirectoryPort
import com.dogancaglar.paymentservice.ports.outbound.CentralDbTransactionalFacadePort
import com.dogancaglar.paymentservice.ports.outbound.IdGeneratorPort
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import com.dogancaglar.paymentservice.ports.outbound.PaymentRepository
import com.dogancaglar.paymentservice.ports.outbound.PaymentTxPort
import com.dogancaglar.paymentservice.ports.outbound.TransferRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Journal ids are the durable idempotency key of the ledger (insert skips on duplicate id).
 * These tests pin that each business record gets its own journal id:
 *  - one journal per internal transfer (two transfers on the same route must not collide),
 *  - one journal per capture (multiple captures of one payment must not collide),
 * while a redelivery of the SAME record still produces the same id.
 */
class ProcessPspResultProcessingServiceTest {

    private lateinit var centralDbTransactionalFacadePort: CentralDbTransactionalFacadePort
    private lateinit var accountDirectory: AccountDirectoryPort
    private lateinit var paymentTxPort: PaymentTxPort
    private lateinit var idGeneratorPort: IdGeneratorPort
    private lateinit var paymentRepository: PaymentRepository
    private lateinit var transferRepository: TransferRepository
    private lateinit var outboxEventFactoryPort: OutboxEventFactoryPort
    private lateinit var service: ProcessPspResultProcessingService

    private val eur = Currency("EUR")
    private val merchant = "MARKETPLACE-5"
    private val paymentIntentId = PaymentIntentId(230392644730224640L)
    private val publicPaymentIntentId = PublicIdFactory.publicPaymentIntentId(paymentIntentId.value)
    private val paymentId = PaymentId(230392875798626304L)

    private val suspenseCode = "CAPTURE_SUSPENSE.MARKETPLACE-5.EUR"
    private val commissionCode = "MERCHANT_COMMISSION_PAYABLE.MARKETPLACE-5.EUR"

    @BeforeEach
    fun setUp() {
        centralDbTransactionalFacadePort = mockk(relaxed = true)
        accountDirectory = mockk()
        paymentTxPort = mockk()
        idGeneratorPort = mockk()
        paymentRepository = mockk()
        transferRepository = mockk()
        outboxEventFactoryPort = mockk(relaxed = true)

        // JournalEntry requires globalJournalEntryId > 0, so hand out increasing ids
        val nextId = AtomicLong(1000L)
        every { idGeneratorPort.generateId() } answers { nextId.incrementAndGet() }

        every { accountDirectory.getAccountByCode(suspenseCode) } returns
            profile(suspenseCode, LedgerAccountType.CAPTURE_SUSPENSE)
        every { accountDirectory.getAccountByCode(commissionCode) } returns
            profile(commissionCode, LedgerAccountType.MERCHANT_COMMISSION_PAYABLE)

        service = ProcessPspResultProcessingService(
            centralDbTransactionalFacadePort,
            accountDirectory,
            paymentTxPort,
            idGeneratorPort,
            paymentRepository,
            transferRepository,
            outboxEventFactoryPort,
            mockk() // merchant lookup: not used by these tests (they don't process an authorization)
        )
    }

    @Test
    fun `two internal transfers on the same route get different journal ids`() {
        // Given - two Commission splits of one payment: same source, same target, same amount, different transfers
        every { paymentRepository.findByPaymentIntentId(paymentIntentId) } returns payment(PaymentStatus.CAPTURED)
        val firstTransferId = 230392893750247424L
        val secondTransferId = 230392893804773376L
        stubTransfer(firstTransferId)
        stubTransfer(secondTransferId)

        // When
        service.processInternalTransferCommand(transferCommand(firstTransferId))
        service.processInternalTransferCommand(transferCommand(secondTransferId))

        // Then
        val journalIds = recordedInternalTransferJournalIds()
        assertEquals(2, journalIds.size)
        assertNotEquals(journalIds[0], journalIds[1], "each transfer must get its own journal id")
        assertTrue(journalIds[0].contains(PublicIdFactory.publicInternalTransferId(firstTransferId)))
        assertTrue(journalIds[1].contains(PublicIdFactory.publicInternalTransferId(secondTransferId)))
    }

    @Test
    fun `redelivered internal transfer command keeps the same journal id`() {
        // Given
        every { paymentRepository.findByPaymentIntentId(paymentIntentId) } returns payment(PaymentStatus.CAPTURED)
        val transferId = 230392893750247424L
        stubTransfer(transferId)

        // When - same command processed twice (at-least-once delivery)
        service.processInternalTransferCommand(transferCommand(transferId))
        service.processInternalTransferCommand(transferCommand(transferId))

        // Then - identical id, so the ledger insert skips the duplicate
        val journalIds = recordedInternalTransferJournalIds()
        assertEquals(2, journalIds.size)
        assertEquals(journalIds[0], journalIds[1])
    }

    @Test
    fun `two captures of one payment get different journal ids`() {
        // Given
        every { paymentRepository.findByPaymentIntentId(paymentIntentId) } returns
            payment(PaymentStatus.SENT_FOR_SETTLE)
        every { accountDirectory.getAccountProfile(LedgerAccountType.CAPTURE_SUSPENSE, merchant, eur) } returns
            profile(suspenseCode, LedgerAccountType.CAPTURE_SUSPENSE)
        every { accountDirectory.getAccountProfile(LedgerAccountType.AUTH_RECEIVABLE, merchant, eur) } returns
            profile("AUTH_RECEIVABLE.MARKETPLACE-5.EUR", LedgerAccountType.AUTH_RECEIVABLE)
        every { accountDirectory.getAccountProfile(LedgerAccountType.AUTH_LIABILITY, merchant, eur) } returns
            profile("AUTH_LIABILITY.MARKETPLACE-5.EUR", LedgerAccountType.AUTH_LIABILITY)
        every { accountDirectory.getAccountProfile(LedgerAccountType.PSP_RECEIVABLE, "GLOBAL", eur) } returns
            profile("PSP_RECEIVABLE.GLOBAL.EUR", LedgerAccountType.PSP_RECEIVABLE)

        val firstCaptureTxId = 230392885156118528L
        val secondCaptureTxId = 230392885156118999L

        // When - each confirmation finds its own pending capture tx
        every { paymentTxPort.findByPaymentId(paymentId.value) } returns listOf(pendingCaptureTx(firstCaptureTxId))
        service.processCaptureConfirmed(captureConfirmed())
        every { paymentTxPort.findByPaymentId(paymentId.value) } returns listOf(pendingCaptureTx(secondCaptureTxId))
        service.processCaptureConfirmed(captureConfirmed())

        // Then
        val journalIds = recordedCaptureJournalIds()
        assertEquals(2, journalIds.size)
        assertNotEquals(journalIds[0], journalIds[1], "each capture must get its own journal id")
        assertTrue(journalIds[0].contains(firstCaptureTxId.toString()))
        assertTrue(journalIds[1].contains(secondCaptureTxId.toString()))
    }

    // ---------------------------------------------------------------- helpers

    private fun recordedInternalTransferJournalIds(): List<String> {
        val batches = mutableListOf<List<JournalEntry>>()
        verify {
            centralDbTransactionalFacadePort.recordInternalTransferOperationInLedger(
                any(),
                capture(batches),
                any()
            )
        }
        return journalIds(batches, JournalType.INTERNAL_TRANSFER)
    }

    private fun recordedCaptureJournalIds(): List<String> {
        val batches = mutableListOf<List<JournalEntry>>()
        verify {
            centralDbTransactionalFacadePort.recordPaymentOperationInLedger(
                any(),
                any(),
                capture(batches),
                any()
            )
        }
        return journalIds(batches, JournalType.CAPTURE)
    }

    private fun journalIds(batches: List<List<JournalEntry>>, type: JournalType): List<String> {
        val ids = mutableListOf<String>()
        for (batch in batches) {
            for (entry in batch) {
                if (entry.journalType == type) {
                    ids.add(entry.id)
                }
            }
        }
        return ids
    }

    private fun stubTransfer(transferId: Long) {
        val now = Utc.nowLocalDateTime()
        every { transferRepository.findById(InternalTransferId(transferId)) } returns InternalTransfer.rehydrate(
            transferId = InternalTransferId(transferId),
            paymentId = paymentId,
            paymentIntentId = paymentIntentId,
            merchantAccount = merchant,
            amount = Amount.of(100, eur),
            targetAccount = commissionCode,
            sourceAccount = suspenseCode,
            status = InternalTransferStatus.SENT_FOR_TRANSFER,
            transferType = JournalType.INTERNAL_TRANSFER.name,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun transferCommand(transferId: Long) = InternalTransferCommand(
        transferId = transferId,
        amountValue = 100,
        currency = "EUR",
        targetAccount = commissionCode,
        sourceAccount = suspenseCode,
        journalType = JournalType.INTERNAL_TRANSFER.name,
        status = InternalTransferStatus.SENT_FOR_TRANSFER.name,
        paymentIntentId = paymentIntentId.value.toString(),
        publicPaymentIntentId = publicPaymentIntentId,
        merchantAccount = merchant
    )

    private fun captureConfirmed() = CaptureConfirmed(
        paymentIntentId = paymentIntentId.value.toString(),
        publicPaymentIntentId = publicPaymentIntentId,
        merchantAccount = merchant,
        amountValue = 3000,
        currency = "EUR"
    )

    private fun pendingCaptureTx(txId: Long): Tx = Tx.createCaptureTx(
        txId = TxId(txId),
        payment = payment(PaymentStatus.SENT_FOR_SETTLE),
        authorizationTxId = TxId(230392875798626305L),
        acquirerReference = "sim_cap_$txId",
        amount = Amount.of(3000, eur)
    )

    private fun payment(status: PaymentStatus): Payment {
        val now = Utc.nowLocalDateTime()
        return Payment.rehydrate(
            paymentId = paymentId,
            paymentIntentId = paymentIntentId,
            buyerId = BuyerId("BUYER-1450"),
            merchantAccount = merchant,
            processingModel = ProcessingModel.DIRECT_MERCHANT,
            totalAmount = Amount.of(3000, eur),
            capturedAmount = Amount.zero(eur),
            refundedAmount = Amount.zero(eur),
            status = status,
            splits = emptyList(),
            createdAt = now,
            updatedAt = now
        )
    }

    private fun profile(code: String, type: LedgerAccountType) = AccountProfile(
        accountCode = code,
        type = type,
        masterAccountCode = merchant,
        subEntityId = null,
        currency = eur,
        status = AccountStatus.ACTIVE
    )
}
