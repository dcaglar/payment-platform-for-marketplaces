package com.dogancaglar.paymentservice.infra.adapter.outbound.serialization

import com.dogancaglar.common.kafka.metadata.PaymentEventMetadataCatalog
import com.dogancaglar.paymentservice.ports.outbound.SerializationPort
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class JacksonSerializationAdapter(
    @param:Qualifier("myObjectMapper") val objectMapper: ObjectMapper
) : SerializationPort {

    init {
        PaymentEventMetadataCatalog.all.forEach {
            objectMapper.registerSubtypes(NamedType(it.clazz, it.eventType))
        }
    }

    override fun <T> toJson(value: T): String = objectMapper.writeValueAsString(value)

    override fun <T> fromJson(json: String, clazz: Class<T>): T =
        objectMapper.readValue(json, clazz)
}
