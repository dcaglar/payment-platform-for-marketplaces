package com.dogancaglar.paymentservice.infra.adapter.outbound.id

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SnowflakeCoreMonotonicTest {

    private val props = IdGenerationProperties(
        epochMillis = 1735689600000L, // 2025-01-01
        regionId = 1
    )

    @Test
    fun `snowflake IDs must increase for the same nodeId`() {
        val core = SnowflakeCore(props.epochMillis, props.regionId)
        val nodeId = 3

        val id1 = core.nextId(nodeId)
        val id2 = core.nextId(nodeId)
        val id3 = core.nextId(nodeId)

        assertTrue(id1 < id2, "Expected id1 < id2 but got $id1 >= $id2")
        assertTrue(id2 < id3, "Expected id2 < id3 but got $id2 >= $id3")
    }
}
