package com.dogancaglar.paymentservice.adapter.inbound.kafka

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.event.EventEnvelopeFactory
import com.dogancaglar.common.event.metadata.EventMetaDataRegistry
import com.dogancaglar.common.kafka.metadata.PaymentEventMetadataCatalog
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.common.kafka.serde.EventEnvelopeKafkaDeserializer
import com.dogancaglar.paymentservice.application.dto.PaymentSplitDto
import com.dogancaglar.paymentservice.application.events.AuthorizationDetails
import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.application.events.JournalEntryEventData
import com.dogancaglar.paymentservice.application.events.PaymentAuthorized
import com.dogancaglar.paymentservice.application.events.PostingDirection
import com.dogancaglar.paymentservice.application.events.PostingEventData
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.infra.adapter.outbound.serialization.JacksonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The authorization's events survive the trip the real system makes: serialized into the outbox payload,
 * routed by event type, and read back by the consumer's Kafka deserializer unchanged.
 */
class PspAuthorizationResultWireTest {

    private val registry = EventMetaDataRegistry(PaymentEventMetadataCatalog.all)
    private val at = Instant.parse("2026-10-02T10:15:30Z")
    private val splits = listOf(
        PaymentSplitDto.of(
            accountType = "BalanceAccount",
            account = "SELLER-5-1",
            amountValue = 1400,
            currency = "EUR"
        ),
        PaymentSplitDto.of(accountType = "Commission", account = "MARKETPLACE-5", amountValue = 100, currency = "EUR")
    )

    @Test
    fun `payment_authorized round-trips with order id, PSP reference and the card's brand and last 4`() {
        val authorized = PaymentAuthorized(
            paymentIntentId = "231538886965329920",
            publicPaymentIntentId = "pi_AzaXVnPCAAA",
            merchantAccount = "MARKETPLACE-5",
            buyerId = "BUYER-1450",
            orderId = "ORDER-1450",
            pspReference = "psp_ref_1",
            processingModel = "MARKETPLACE",
            totalAmountValue = 1500,
            currency = "EUR",
            splits = splits,
            cardBrand = "VISA",
            cardLast4 = "4242",
            timestamp = at
        )

        val read = roundTrip(authorized)

        assertEquals(Topics.PSP_RESULTS, registry.metadataFor<Event>("payment_authorized").topic)
        assertEquals(authorized, read.data)
    }

    @Test
    fun `the AUTHORIZATION journal event carries who and what the payment is, other bookings carry none`() {
        val authorized = PaymentAuthorized(
            paymentIntentId = "231538886965329920",
            publicPaymentIntentId = "pi_AzaXVnPCAAA",
            merchantAccount = "MARKETPLACE-5",
            buyerId = "BUYER-1450",
            orderId = "ORDER-1450",
            pspReference = "psp_ref_1",
            processingModel = "MARKETPLACE",
            totalAmountValue = 1500,
            currency = "EUR",
            splits = splits,
            cardBrand = "VISA",
            cardLast4 = "4242",
            timestamp = at
        )
        val entry = JournalEntryEventData.create(
            journalEntryId = "AUTH:pi_AzaXVnPCAAA",
            globalJournalEntryId = 3001L,
            journalType = JournalType.AUTHORIZATION,
            journalName = null,
            paymentId = 1001L,
            txId = 2001L,
            reason = null,
            createdAt = at,
            postings = listOf(
                PostingEventData.create(
                    "AUTH_RECEIVABLE.MARKETPLACE-5.EUR",
                    LedgerAccountType.AUTH_RECEIVABLE,
                    1500,
                    "EUR",
                    PostingDirection.DEBIT
                ),
                PostingEventData.create(
                    "AUTH_LIABILITY.MARKETPLACE-5.EUR",
                    LedgerAccountType.AUTH_LIABILITY,
                    1500,
                    "EUR",
                    PostingDirection.CREDIT
                )
            )
        )
        val withDetails = JournalEntriesRecorded.from(
            cmd = authorized,
            batchId = "AUTHORIZATION:pi_AzaXVnPCAAA:2001",
            entries = listOf(entry),
            customPartitionKey = "MARKETPLACE-5",
            now = at,
            authorization = AuthorizationDetails.from(authorized)
        )
        val withoutDetails = JournalEntriesRecorded.from(
            cmd = authorized,
            batchId = "CAPTURE:pi_AzaXVnPCAAA:2002",
            entries = listOf(entry),
            customPartitionKey = "MARKETPLACE-5",
            now = at
        )

        val readWith = roundTrip(withDetails, Topics.JOURNAL_ENTRIES_RECORDED).data as JournalEntriesRecorded
        val readWithout = roundTrip(withoutDetails, Topics.JOURNAL_ENTRIES_RECORDED).data as JournalEntriesRecorded

        assertEquals(withDetails, readWith)
        assertEquals("VISA", readWith.authorization!!.cardBrand)
        assertEquals("4242", readWith.authorization!!.cardLast4)
        assertEquals("ORDER-1450", readWith.authorization!!.orderId)
        assertEquals("psp_ref_1", readWith.authorization!!.pspReference)
        assertEquals("BUYER-1450", readWith.authorization!!.buyerId)
        assertEquals(splits, readWith.authorization!!.splits)
        assertEquals(null, readWithout.authorization)
    }

    private fun roundTrip(event: Event, topic: String = Topics.PSP_RESULTS): EventEnvelope<Event> {
        val envelope = EventEnvelopeFactory.envelopeFor(
            data = event,
            aggregateId = "pi_AzaXVnPCAAA",
            parentEventId = null
        )
        val payload = JacksonUtil.createObjectMapper().writeValueAsBytes(envelope)
        @Suppress("UNCHECKED_CAST")
        return EventEnvelopeKafkaDeserializer().deserialize(topic, payload) as EventEnvelope<Event>
    }
}
