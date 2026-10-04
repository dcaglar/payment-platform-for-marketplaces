package com.dogancaglar.paymentservice.adapter.inbound.kafka

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.event.EventEnvelopeFactory
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.common.kafka.serde.EventEnvelopeKafkaDeserializer
import com.dogancaglar.paymentservice.application.command.CreateAccountCommand
import com.dogancaglar.paymentservice.application.events.AccountCreationRequested
import com.dogancaglar.paymentservice.domain.model.account.Address
import com.dogancaglar.paymentservice.domain.model.account.PlatformFee
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.infra.adapter.outbound.serialization.JacksonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The account request survives the trip the real system makes: serialized into the outbox payload,
 * read back by the consumer's Kafka deserializer, and turned into the same command.
 */
class AccountCreationRequestedWireTest {

    @Test
    fun `request round-trips from outbox payload to consumer command`() {
        val cmd = CreateAccountCommand(
            merchantAccountCode = "MARKETPLACE-9",
            legalName = "Marketplace Nine B.V.",
            address = Address.of("Damrak 9", "2nd floor", "Amsterdam", "1012 LG", "NL"),
            industry = "5399",
            currency = Currency("EUR"),
            platformFee = PlatformFee.of(Amount.of(30, Currency("EUR")), 150),
            isAutoCaptured = false,
            isAutoSettled = true,
            sellerAccountCodes = listOf("SELLER-9-1", "SELLER-9-2")
        )
        val envelope = EventEnvelopeFactory.envelopeFor(
            data = AccountCreationRequested.from(cmd),
            aggregateId = cmd.merchantAccountCode,
            parentEventId = null
        )
        val payload = JacksonUtil.createObjectMapper().writeValueAsBytes(envelope)

        @Suppress("UNCHECKED_CAST")
        val read = EventEnvelopeKafkaDeserializer().deserialize(Topics.ACCOUNT_CREATION_REQUESTED, payload)
            as EventEnvelope<AccountCreationRequested>

        assertEquals("account_creation_requested", read.eventType)
        assertEquals("MARKETPLACE-9:account_creation_requested", read.data.deterministicEventId())
        assertEquals(cmd, read.data.toCommand())
    }
}
