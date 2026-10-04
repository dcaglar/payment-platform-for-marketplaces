package com.dogancaglar.common.id

import java.util.Base64

object PublicIdCodec {

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(id: Long): String {
        val bytes = ByteArray(8)
        var v = id
        for (i in 7 downTo 0) {
            bytes[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return encoder.encodeToString(bytes)
    }

    fun decode(encoded: String): Long {
        val bytes = decoder.decode(encoded)
        var v = 0L
        for (b in bytes) {
            v = (v shl 8) or (b.toLong() and 0xFF)
        }
        return v
    }
}
