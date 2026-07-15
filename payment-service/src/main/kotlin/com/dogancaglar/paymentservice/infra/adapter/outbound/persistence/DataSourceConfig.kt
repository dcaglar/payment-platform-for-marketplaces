package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.zaxxer.hikari.HikariDataSource
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.hikaricp.v3_0.HikariTelemetry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class DataSourceConfig {

    @Bean(name = ["edgeDataSource"])
    @ConfigurationProperties(prefix = "spring.datasource.edge")
    fun edgeDataSource(
        @Value("\${spring.datasource.edge.url}") url: String,
        @Value("\${spring.datasource.edge.username}") user: String,
        @Value("\${spring.datasource.edge.password}") pass: String,
        @Value("\${spring.datasource.edge.driver-class-name}") driver: String,
        openTelemetry: OpenTelemetry
    ): HikariDataSource {
        val datasource =  DataSourceBuilder.create()
            .type(HikariDataSource::class.java)
            .url(url)
            .username(user)
            .password(pass)
            .driverClassName(driver)
            .build().also {
                it.poolName = "payment-service-web-edge-db"
                it.metricsTrackerFactory = HikariTelemetry.create(openTelemetry).createMetricsTrackerFactory()
            }
        return datasource
    }

}