package com.dogancaglar.paymentservice.ports.outbound

interface SerializationPort {
    fun <T> toJson(value: T): String
    fun <T> fromJson(json: String, clazz: Class<T>): T
}
