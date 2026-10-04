package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import java.time.Instant

interface CentralOutboxForwarderPort {
    fun insertBatch(edgeNodeId: String, entries: List<OutboxEvent>)
    fun updateWatermark(edgeNodeId: String, forwardedUpTo: Instant)
    fun deleteWatermark(edgeNodeId: String)
    fun isSchemaReady(): Boolean
}
