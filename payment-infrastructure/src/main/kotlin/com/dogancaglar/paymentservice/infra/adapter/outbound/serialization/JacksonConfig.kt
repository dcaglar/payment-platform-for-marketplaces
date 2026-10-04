package com.dogancaglar.paymentservice.infra.adapter.outbound.serialization

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
class JacksonConfig {
    @Bean("myObjectMapper")
    @Primary
    fun objectMapper(): ObjectMapper = JacksonUtil.createObjectMapper()
}
