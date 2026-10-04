package com.dogancaglar.paymentservice.ports.outbound

interface IdGeneratorPort {
    fun generateId(): Long
}
