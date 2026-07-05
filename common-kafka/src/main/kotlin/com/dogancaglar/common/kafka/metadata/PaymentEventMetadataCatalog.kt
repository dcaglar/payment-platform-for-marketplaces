package com.dogancaglar.common.kafka.metadata

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.event.metadata.EventMetadata
import com.dogancaglar.paymentservice.application.events.PaymentAuthorized
import com.fasterxml.jackson.core.type.TypeReference
import com.dogancaglar.paymentservice.application.events.CaptureRequested
import com.dogancaglar.paymentservice.application.events.CaptureConfirmed
import com.dogancaglar.paymentservice.application.events.EventType
import com.dogancaglar.paymentservice.application.events.CaptureSubmitted
import com.dogancaglar.paymentservice.application.events.InternalTransferCommand
import com.dogancaglar.paymentservice.application.events.JournalEntriesRecorded
import com.dogancaglar.paymentservice.application.events.SettlementReceived

object PaymentEventMetadataCatalog {

    // 1. Routes to shared PSP_RESULTS topic
    object PaymentAuthorizedMetadata : EventMetadata<PaymentAuthorized> {
        override val topic = Topics.PSP_RESULTS
        override val eventType = EventType.PAYMENT_AUTHORIZED
        override val clazz = PaymentAuthorized::class.java
        override val typeRef = object : TypeReference<EventEnvelope<PaymentAuthorized>>() {}
    }

    // 2. Routes to CAPTURE_COMMANDS for the PaymentCaptureExecutor
    object CaptureRequestedMetadata : EventMetadata<CaptureRequested> {
        override val topic = Topics.CAPTURE_REQUESTED
        override val eventType = EventType.CAPTURE_REQUESTED
        override val clazz = CaptureRequested::class.java
        override val typeRef = object : TypeReference<EventEnvelope<CaptureRequested>>() {}
    }

    // 3. Routes to CAPTURE_SUBMITTED_ACKS (The HTTP 202)
    object CaptureSubmittedMetadata : EventMetadata<CaptureSubmitted> {
        override val topic = Topics.CAPTURE_SUBMITTED_ACKS
        override val eventType = EventType.CAPTURE_SUBMITTED
        override val clazz = CaptureSubmitted::class.java
        override val typeRef = object : TypeReference<EventEnvelope<CaptureSubmitted>>() {}
    }

    // 4. Routes to shared PSP_RESULTS topic (The Adyen Webhook)
    object CaptureConfirmedMetadata : EventMetadata<CaptureConfirmed> {
        override val topic = Topics.PSP_RESULTS
        override val eventType = EventType.CAPTURE_CONFIRMED
        override val clazz = CaptureConfirmed::class.java
        override val typeRef = object : TypeReference<EventEnvelope<CaptureConfirmed>>() {}
        // Use publicPaymentIntentId here to ensure it lands in the exact same partition as PaymentAuthorized
    }
        //publish LEdgerEntriesRecorded
    object JournalEntriesRecordedMetadata : EventMetadata<JournalEntriesRecorded> {
        override val topic = Topics.JOURNAL_ENTRIES_RECORDED
        override val eventType = EventType.JOURNAL_ENTRIES_RECORDED
        override val clazz = JournalEntriesRecorded::class.java
        override val typeRef = object : TypeReference<EventEnvelope<JournalEntriesRecorded>>() {}
    }

    // 5. Routes to PSP RESULT
    object InternalTransferCommandMetadata : EventMetadata<InternalTransferCommand> {
        override val topic = Topics.PSP_RESULTS
        override val eventType = EventType.INTERNAL_TRANSFER_COMMAND
        override val clazz = InternalTransferCommand::class.java
        override val typeRef = object : TypeReference<EventEnvelope<InternalTransferCommand>>() {}
    }

    // 6. Routes simulated SDR results to PSP_RESULTS
    object SettlementLineReconciledMetadata : EventMetadata<SettlementReceived> {
        override val topic = Topics.PSP_RESULTS
        override val eventType = EventType.SETTLEMENT_RECEIVED
        override val clazz = SettlementReceived::class.java
        override val typeRef = object : TypeReference<EventEnvelope<SettlementReceived>>() {}
    }
    val all: List<EventMetadata<*>> = listOf(
        PaymentAuthorizedMetadata,
        CaptureRequestedMetadata,
        CaptureSubmittedMetadata,
        CaptureConfirmedMetadata,
        InternalTransferCommandMetadata,
        JournalEntriesRecordedMetadata,
        SettlementLineReconciledMetadata
    )

}