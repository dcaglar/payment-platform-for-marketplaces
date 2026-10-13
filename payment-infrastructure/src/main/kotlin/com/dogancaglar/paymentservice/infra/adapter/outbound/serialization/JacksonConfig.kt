package com.dogancaglar.paymentservice.infra.adapter.outbound.serialization

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.json.JsonMapper

@Configuration
class JacksonConfig {
    /** A JsonMapper bean: Boot 4 backs off its own and builds the HTTP converters from this one. */
    @Bean("myObjectMapper")
    @Primary
    fun objectMapper(): JsonMapper = JacksonUtil.createObjectMapper()
}
