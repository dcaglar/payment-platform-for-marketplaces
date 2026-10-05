package com.dogancaglar.common.id

import java.util.Base64

object PublicIdCodec {
    private const val BYTE_MASK = 0xFFL

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(id: Long): String {
        val bytes = ByteArray(Long.SIZE_BYTES)
        var v = id
        for (i in Long.SIZE_BYTES - 1 downTo 0) {
            bytes[i] = (v and BYTE_MASK).toByte()
            v = v ushr Byte.SIZE_BITS
        }
        return encoder.encodeToString(bytes)
    }

    fun decode(encoded: String): Long {
        val bytes = decoder.decode(encoded)
        var v = 0L
        for (b in bytes) {
            v = (v shl Byte.SIZE_BITS) or (b.toLong() and BYTE_MASK)
        }
        return v
    }
}
