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
        return try {
            lastPart.toInt() % 32
        } catch (e: Exception) {
            // Fallback for non-StatefulSet pods (hashCode)
            (podName.hashCode() and Int.MAX_VALUE) % 32
        }
    }
}
