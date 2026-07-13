package com.dogancaglar.common.id

object PublicIdFactory {

    fun publicPaymentId(paymentId: Long): String =
        "pay_${PublicIdCodec.encode(paymentId)}"

    fun publicPaymentIntentId(paymentIntentId: Long): String =
        "pi_${PublicIdCodec.encode(paymentIntentId)}"

    fun publicInternalTransferId(internalTransferId: Long): String =
        "tr_${PublicIdCodec.encode(internalTransferId)}"

    fun publicLedgerEntryId(id: Long): String =
        "le_${PublicIdCodec.encode(id)}"

    // reverse (if ever needed for admin APIs)
    fun toInternalId(publicId: String): Long {
        val idx = publicId.indexOf('_')
        require(idx > 0) { "Invalid publicId: $publicId" }
        val encoded = publicId.substring(idx + 1)
        return PublicIdCodec.decode(encoded)
    }
}