package com.dogancaglar.paymentservice.infra.adapter.outbound.serialization

import com.dogancaglar.paymentservice.ports.outbound.SerializationPort
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class JacksonSerializationAdapter(
    @param:Qualifier("myObjectMapper") val objectMapper: ObjectMapper
) : SerializationPort {

    override fun <T> toJson(value: T): String = objectMapper.writeValueAsString(value)

    override fun <T> fromJson(json: String, clazz: Class<T>): T =
        objectMapper.readValue(json, clazz)
}
