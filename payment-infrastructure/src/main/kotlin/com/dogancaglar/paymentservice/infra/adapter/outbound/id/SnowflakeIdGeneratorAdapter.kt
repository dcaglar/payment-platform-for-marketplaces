package com.dogancaglar.paymentservice.infra.adapter.outbound.id

import com.dogancaglar.paymentservice.ports.outbound.IdGeneratorPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class SnowflakeIdGeneratorAdapter(
    private val props: IdGenerationProperties,
    @Value("\${HOSTNAME:payment-service-0}") private val podName: String
) : IdGeneratorPort {

    private val core = SnowflakeCore(props.epochMillis, props.regionId)
    private val nodeId = parseNodeId(podName)

    override fun generateId(): Long = core.nextId(nodeId)

    private fun parseNodeId(podName: String): Int {
        val parts = podName.split("-")
        val lastPart = parts.last()
        val ordinal = lastPart.toIntOrNull()
        if (ordinal == null) {
            // Fallback for non-StatefulSet pods (hashCode)
            return (podName.hashCode() and Int.MAX_VALUE) % 32
        }
        return ordinal % 32
    }
}
