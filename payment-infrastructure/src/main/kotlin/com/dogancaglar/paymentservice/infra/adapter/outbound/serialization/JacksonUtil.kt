package com.dogancaglar.paymentservice.infra.adapter.outbound.serialization

import com.dogancaglar.common.kafka.metadata.PaymentEventMetadataCatalog
import org.slf4j.LoggerFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.jsontype.NamedType
import tools.jackson.module.kotlin.kotlinModule

object JacksonUtil {
    private val logger = LoggerFactory.getLogger(JacksonUtil::class.java)

    /**
     * The one mapper of the platform: outbox payloads, Redis entries, split columns and the HTTP API all go
     * through it. Jackson 3 mappers are immutable, so everything is set on the builder: java.time support is
     * built in, dates are written as ISO strings, unknown properties are ignored, and the event payload
     * classes are registered under their event type names.
     */
    fun createObjectMapper(): JsonMapper {
        val builder = JsonMapper.builder()
            .addModule(kotlinModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        for (metadata in PaymentEventMetadataCatalog.all) {
            builder.registerSubtypes(NamedType(metadata.clazz, metadata.eventType))
        }
        val objectMapper = builder.build()
        logger.debug("📦 Objectmapper config using ObjectMapper: $objectMapper")
        return objectMapper
    }
}
