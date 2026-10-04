package com.dogancaglar.paymentservice.application.util

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.paymentservice.application.events.CaptureRequested

data class RetryItem(
    val envelope: EventEnvelope<CaptureRequested>,
    val raw: ByteArray
)
