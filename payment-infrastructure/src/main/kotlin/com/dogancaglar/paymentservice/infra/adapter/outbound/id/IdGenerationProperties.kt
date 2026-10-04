package com.dogancaglar.paymentservice.infra.adapter.outbound.id

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "payments.id")
data class IdGenerationProperties(
    /**
     * Custom epoch in millis, e.g. 2025-01-01T00:00:00Z.
     */
    val epochMillis: Long = 1735689600000L, // adjust if you like
    /**
     * Region / DC id (0..31), e.g. EU1=1, US1=2...
     */
    val regionId: Int = 1
)
