package com.dogancaglar.paymentservice.infra.adapter.outbound.serialization

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory

object JacksonUtil {
    private val logger = LoggerFactory.getLogger(JacksonUtil::class.java)

    fun createObjectMapper(): ObjectMapper {
        val objectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) // ← Add this line
            .registerKotlinModule()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        logger.debug("📦 Objectmapper config using ObjectMapper: $objectMapper")
        return objectMapper
    }
}
