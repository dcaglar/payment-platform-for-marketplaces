// src/main/kotlin/.../id/SnowflakeCore.kt
package com.dogancaglar.paymentservice.infra.adapter.outbound.id

open class SnowflakeCore(
    private val epochMillis: Long,
    private val regionId: Int
) {
    init {
        require(regionId in 0..MAX_REGION_ID) {
            "regionId must be between 0 and $MAX_REGION_ID (was $regionId)"
        }
    }

    /**
     * State per logical nodeId
     */
    private data class NodeState(
        var lastTimestamp: Long = -1L,
        var sequence: Int = 0
    )

    private val nodeStates: Array<NodeState> = Array(MAX_NODE_ID + 1) { NodeState() }

    /**
     * Generate a new Snowflake ID for the given nodeId (0..31).
     */
    @Synchronized
    open fun nextId(nodeId: Int): Long {
        require(nodeId in 0..MAX_NODE_ID) {
            "nodeId must be between 0 and $MAX_NODE_ID (was $nodeId)"
        }

        val state = nodeStates[nodeId]

        var now = currentTimeMillis()
        val lastTs = state.lastTimestamp

        if (now < lastTs) {
            // clock went backwards – clamp to lastTs
            now = lastTs
        }

        if (now == lastTs) {
            // same millisecond → increment sequence
            val nextSeq = (state.sequence + 1) and MAX_SEQUENCE
            if (nextSeq == 0) {
                // sequence overflow → wait next millisecond
                now = waitNextMillis(lastTs)
            }
            state.sequence = nextSeq
        } else {
            // moved forward in time
            state.sequence = 0
        }

        state.lastTimestamp = now

        val tsPart = (now - epochMillis) shl TIMESTAMP_SHIFT
        val regionPart = (regionId.toLong() shl REGION_SHIFT)
        val nodePart = (nodeId.toLong() shl NODE_SHIFT)
        val seqPart = (state.sequence.toLong() shl SEQUENCE_SHIFT)

        return tsPart or regionPart or nodePart or seqPart
    }

    fun extractNodeId(id: Long): Int =
        ((id shr NODE_SHIFT) and NODE_MASK.toLong()).toInt()

    fun extractRegionId(id: Long): Int =
        ((id shr REGION_SHIFT) and REGION_MASK.toLong()).toInt()

    fun extractTimestampMillis(id: Long): Long {
        val ts = (id shr TIMESTAMP_SHIFT) and TIMESTAMP_MASK
        return ts + epochMillis
    }

    private fun currentTimeMillis(): Long = System.currentTimeMillis()

    private fun waitNextMillis(lastTs: Long): Long {
        var now = currentTimeMillis()
        while (now <= lastTs) {
            now = currentTimeMillis()
        }
        return now
    }

    companion object {
        // Bit widths
        private const val SEQUENCE_BITS = 12
        private const val NODE_BITS = 5
        private const val REGION_BITS = 5
        private const val TIMESTAMP_BITS = 41 // implied

        // Masks
        private const val MAX_SEQUENCE = (1 shl SEQUENCE_BITS) - 1 // 4095
        private const val MAX_NODE_ID = (1 shl NODE_BITS) - 1 // 31
        private const val MAX_REGION_ID = (1 shl REGION_BITS) - 1 // 31

        private const val SEQUENCE_SHIFT = 0
        private const val NODE_SHIFT = SEQUENCE_BITS
        private const val REGION_SHIFT = NODE_SHIFT + NODE_BITS
        private const val TIMESTAMP_SHIFT = REGION_SHIFT + REGION_BITS

        private const val NODE_MASK = MAX_NODE_ID
        private const val REGION_MASK = MAX_REGION_ID
        private const val TIMESTAMP_MASK = (1L shl TIMESTAMP_BITS) - 1
    }
}
