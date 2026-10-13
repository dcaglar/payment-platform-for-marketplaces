package com.dogancaglar.common.kafka.serde

import com.dogancaglar.common.event.EventEnvelope
import org.apache.kafka.common.serialization.Serializer
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class EventEnvelopeKafkaSerializer : Serializer<EventEnvelope<*>> {

    val objectMapper: ObjectMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    override fun serialize(topic: String?, data: EventEnvelope<*>?): ByteArray? {
        return if (data == null) null else objectMapper.writeValueAsBytes(data)
    }
}
