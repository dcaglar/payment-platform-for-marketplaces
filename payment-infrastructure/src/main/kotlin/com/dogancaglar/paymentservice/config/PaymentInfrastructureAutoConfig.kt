package com.dogancaglar.paymentservice.config

import com.dogancaglar.paymentservice.infra.adapter.outbound.id.IdGenerationProperties
import com.dogancaglar.paymentservice.infra.adapter.outbound.redis.RedisConfig
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import

@AutoConfiguration
@EnableConfigurationProperties(IdGenerationProperties::class)
@Import(
    RedisConfig::class,
    // etc...
)
class PaymentInfrastructureAutoConfig
