package com.dogancaglar.paymentservice.infra.adapter.outbound.id

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SnowflakeIdGeneratorAdapterTest {

    private lateinit var props: IdGenerationProperties
    private lateinit var adapter: SnowflakeIdGeneratorAdapter

    @BeforeEach
    fun setup() {
        props = IdGenerationProperties(
            epochMillis = 0,
            regionId = 1
        )
    }

    @Test
    fun `IDs should use pod ordinal for nodeId if available`() {
        val core = object : SnowflakeCore(epochMillis = 0, regionId = 1) {
            override fun nextId(nodeId: Int): Long = (nodeId.toLong() shl 10)
        }

        // Pod name with ordinal -5
        adapter = SnowflakeIdGeneratorAdapter(props, "payment-service-5").apply {
            val coreField = this::class.java.getDeclaredField("core")
            coreField.isAccessible = true
            coreField.set(this, core)
        }

        val id = adapter.generateId()
        val extractedNodeId = id shr 10
        assertEquals(5L, extractedNodeId)
    }

    @Test
    fun `IDs should fallback to hashCode if no ordinal in pod name`() {
        val core = object : SnowflakeCore(epochMillis = 0, regionId = 1) {
            override fun nextId(nodeId: Int): Long = (nodeId.toLong() shl 10)
        }

        // Standard pod name from Deployment (not StatefulSet)
        val podName = "payment-service-fdf87c"
        adapter = SnowflakeIdGeneratorAdapter(props, podName).apply {
            val coreField = this::class.java.getDeclaredField("core")
            coreField.isAccessible = true
            coreField.set(this, core)
        }

        val id = adapter.generateId()
        val extractedNodeId = id shr 10
        val expectedNodeId = (podName.hashCode() and Int.MAX_VALUE) % 32
        assertEquals(expectedNodeId.toLong(), extractedNodeId)
    }

    @Test
    fun `IDs should handle very high ordinals with modulo 32`() {
        val core = object : SnowflakeCore(epochMillis = 0, regionId = 1) {
            override fun nextId(nodeId: Int): Long = (nodeId.toLong() shl 10)
        }

        // Ordinal 33 should result in nodeId 1
        adapter = SnowflakeIdGeneratorAdapter(props, "payment-service-33").apply {
            val coreField = this::class.java.getDeclaredField("core")
            coreField.isAccessible = true
            coreField.set(this, core)
        }

        val id = adapter.generateId()
        val extractedNodeId = id shr 10
        assertEquals(1L, extractedNodeId)
    }
}
