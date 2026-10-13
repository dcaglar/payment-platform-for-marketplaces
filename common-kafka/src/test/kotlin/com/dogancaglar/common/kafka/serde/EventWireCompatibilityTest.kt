package com.dogancaglar.common.kafka.serde

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.event.EventEnvelopeFactory
import com.dogancaglar.common.kafka.metadata.PaymentEventMetadataCatalog
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.paymentservice.application.dto.PaymentSplitDto
import com.dogancaglar.paymentservice.application.events.AccountCreationRequested
import com.dogancaglar.paymentservice.application.events.AuthorizationDetails
import com.dogancaglar.paymentservice.application.events.CaptureConfirmed
import com.dogancaglar.paymentservice.application.events.CaptureRequested
import com.dogancaglar.paymentservice.application.events.CaptureSubmitted
import com.dogancaglar.paymentservice.application.events.InternalTransferCommand
import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.application.events.JournalEntryEventData
import com.dogancaglar.paymentservice.application.events.PaymentAuthorized
import com.dogancaglar.paymentservice.application.events.PostingDirection
import com.dogancaglar.paymentservice.application.events.PostingEventData
import com.dogancaglar.paymentservice.application.events.SettlementReceived
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

/**
 * The bytes of an event on the wire are a contract: the edge writes the outbox payload, the relay streams it to
 * Kafka unchanged, the consumers read it, possibly with an older or newer build than the writer, and rows already
 * in the outbox tables must still be readable after a deploy. One fixed sample per registered event type is kept
 * in src/test/resources/wire/<eventType>.json, recorded by the Jackson 2 code before the Jackson 3 upgrade.
 * This test fails when the serializer writes different bytes or the deserializer no longer reads the recorded ones.
 *
 * After a change to an event made on purpose, re-record with `mvn -pl common-kafka test -Dwire.record=true`
 * and review the diff of the json files.
 */
class EventWireCompatibilityTest {

    private val serializer = EventEnvelopeKafkaSerializer()
    private val deserializer = EventEnvelopeKafkaDeserializer()

    // micros in the timestamp: a precision change in the date format shows up in the bytes
    private val at = Instant.parse("2026-10-02T10:15:30.123456Z")
    private val paymentIntentId = "231538886965329920"
    private val publicPaymentIntentId = "pi_AzaXVnPCAAA"
    private val merchant = "MARKETPLACE-5"

    private val authorized = PaymentAuthorized(
        paymentIntentId = paymentIntentId,
        publicPaymentIntentId = publicPaymentIntentId,
        merchantAccount = merchant,
        buyerId = "BUYER-1450",
        orderId = "ORDER-1450",
        pspReference = "psp_ref_1",
        processingModel = "MARKETPLACE",
        totalAmountValue = 1500,
        currency = "EUR",
        splits = listOf(
            PaymentSplitDto.of(
                accountType = "BalanceAccount",
                account = "SELLER-5-1",
                amountValue = 1400,
                currency = "EUR"
            ),
            PaymentSplitDto.of(accountType = "Commission", account = merchant, amountValue = 100, currency = "EUR")
        ),
        cardBrand = "VISA",
        cardLast4 = "4242",
        timestamp = at
    )

    @Test
    fun `every registered event type has a recorded sample`() {
        val recorded = samples().map { it.eventType }.toSet()
        for (metadata in PaymentEventMetadataCatalog.all) {
            assertTrue(metadata.eventType in recorded, "no wire sample for event type ${metadata.eventType}")
        }
    }

    @Test
    fun `payment_authorized bytes are unchanged`() = assertSameAsRecorded(authorized, Topics.PSP_RESULTS)

    @Test
    fun `capture_requested bytes are unchanged`() = assertSameAsRecorded(
        CaptureRequested(paymentIntentId, publicPaymentIntentId, merchant, 1500, "EUR", attempt = 2, timestamp = at),
        Topics.CAPTURE_REQUESTED
    )

    @Test
    fun `capture_submitted bytes are unchanged`() = assertSameAsRecorded(
        CaptureSubmitted("psp_ref_1", paymentIntentId, publicPaymentIntentId, merchant, 1500, "EUR", timestamp = at),
        Topics.CAPTURE_SUBMITTED_ACKS
    )

    @Test
    fun `capture_confirmed bytes are unchanged`() = assertSameAsRecorded(
        CaptureConfirmed(paymentIntentId, publicPaymentIntentId, merchant, 1500, "EUR", timestamp = at),
        Topics.PSP_RESULTS
    )

    @Test
    fun `journal_entries_recorded bytes are unchanged`() = assertSameAsRecorded(
        JournalEntriesRecorded.from(
            cmd = authorized,
            batchId = "AUTHORIZATION:$publicPaymentIntentId:2001",
            entries = listOf(
                JournalEntryEventData.create(
                    journalEntryId = "AUTH:$publicPaymentIntentId",
                    globalJournalEntryId = 3001L,
                    journalType = JournalType.AUTHORIZATION,
                    journalName = null,
                    paymentId = 1001L,
                    txId = 2001L,
                    reason = null,
                    createdAt = at,
                    postings = listOf(
                        PostingEventData.create(
                            "AUTH_RECEIVABLE.$merchant.EUR",
                            LedgerAccountType.AUTH_RECEIVABLE,
                            1500,
                            "EUR",
                            PostingDirection.DEBIT
                        ),
                        PostingEventData.create(
                            "AUTH_LIABILITY.$merchant.EUR",
                            LedgerAccountType.AUTH_LIABILITY,
                            1500,
                            "EUR",
                            PostingDirection.CREDIT
                        )
                    )
                )
            ),
            customPartitionKey = merchant,
            now = at,
            authorization = AuthorizationDetails.from(authorized)
        ),
        Topics.JOURNAL_ENTRIES_RECORDED
    )

    @Test
    fun `internal_transfer_command bytes are unchanged`() = assertSameAsRecorded(
        InternalTransferCommand(
            transferId = 4001L,
            amountValue = 1400,
            currency = "EUR",
            targetAccount = "SELLER_PAYABLE.$merchant.SELLER-5-1.EUR",
            sourceAccount = "CAPTURE_SUSPENSE.$merchant.EUR",
            journalType = "INTERNAL_TRANSFER",
            status = "SENT_FOR_TRANSFER",
            paymentIntentId = paymentIntentId,
            publicPaymentIntentId = publicPaymentIntentId,
            merchantAccount = merchant,
            timestamp = at
        ),
        Topics.PSP_RESULTS
    )

    @Test
    fun `settlement_received bytes are unchanged`() = assertSameAsRecorded(
        SettlementReceived(
            paymentIntentId = paymentIntentId,
            publicPaymentIntentId = publicPaymentIntentId,
            merchantAccount = merchant,
            grossAmountValue = 1500,
            netCashAmountValue = 1470,
            pspFeeAmountValue = 30,
            currency = "EUR",
            timestamp = at
        ),
        Topics.PSP_RESULTS
    )

    @Test
    fun `account_creation_requested bytes are unchanged`() = assertSameAsRecorded(
        AccountCreationRequested(
            merchantAccountCode = "MARKETPLACE-9",
            legalName = "Marketplace Nine B.V.",
            addressLine1 = "Damrak 9",
            addressLine2 = null,
            city = "Amsterdam",
            postalCode = "1012 LG",
            country = "NL",
            industry = "5399",
            currency = "EUR",
            platformFeeFixed = 30,
            platformFeeBps = 150,
            autoCaptured = false,
            autoSettled = true,
            sellerAccountCodes = listOf("SELLER-9-1", "SELLER-9-2"),
            timestamp = at
        ),
        Topics.ACCOUNT_CREATION_REQUESTED
    )

    private fun samples(): List<Event> = listOf(
        authorized,
        CaptureRequested(paymentIntentId, publicPaymentIntentId, merchant, 1500, "EUR", attempt = 2, timestamp = at),
        CaptureSubmitted("psp_ref_1", paymentIntentId, publicPaymentIntentId, merchant, 1500, "EUR", timestamp = at),
        CaptureConfirmed(paymentIntentId, publicPaymentIntentId, merchant, 1500, "EUR", timestamp = at),
        JournalEntriesRecorded.from(authorized, "b", emptyList(), merchant, at),
        InternalTransferCommand(1, 1, "EUR", "t", "s", "j", "st", paymentIntentId, publicPaymentIntentId, merchant, at),
        SettlementReceived(paymentIntentId, publicPaymentIntentId, merchant, 1, 1, 0, "EUR", at),
        AccountCreationRequested("m", "l", "a", null, "c", "p", "NL", "i", "EUR", 0, 0, false, false, emptyList(), at)
    )

    private fun assertSameAsRecorded(event: Event, topic: String) {
        val envelope = EventEnvelopeFactory.envelopeFor(
            data = event,
            aggregateId = publicPaymentIntentId,
            parentEventId = null,
            timestamp = at
        )
        val written = serializer.serialize(topic, envelope)!!
        val file = Paths.get("src/test/resources/wire/${event.eventType}.json")
        if (System.getProperty("wire.record") == "true") {
            Files.createDirectories(file.parent)
            Files.write(file, written)
        }
        val recorded = Files.readAllBytes(file)
        assertEquals(String(recorded, Charsets.UTF_8), String(written, Charsets.UTF_8), "bytes of ${event.eventType}")

        @Suppress("UNCHECKED_CAST")
        val read = deserializer.deserialize(topic, recorded) as EventEnvelope<Event>
        assertEquals(envelope, read, "recorded ${event.eventType} read back")
    }
}
