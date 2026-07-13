package com.dogancaglar.paymentservice.ports.inbound.usecases

import com.dogancaglar.paymentservice.application.events.CaptureSubmitted

interface RecordCaptureSubmissionUseCase {
    /**
     * Records the factual event of an outbound capture request being accepted by the PSP.
     * Advances states, maps transaction audit roots, and schedules downstream delivery.
     */
    fun recordSubmission(event: CaptureSubmitted, parentEventId: String)
}