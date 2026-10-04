package com.dogancaglar.paymentservice.ports.outbound

interface ExternalIdGeneratorPort {

    fun nextId(namespace: String): Long

    fun getRawValue(namespace: String): Long?

    fun setMinValue(namespace: String, value: Long)
}
