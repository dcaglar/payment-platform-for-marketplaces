package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.liquibase.LiquibaseDataSource
import org.springframework.beans.factory.annotation.Value
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.hikaricp.v3_0.HikariTelemetry

@Configuration
class MultiDataSourceConfig(@Value("\${app.pod-name}") private val podName: String,
                            @Value("\${app.edge-cell-base-url}") private val baseUrl: String,
                            @Value("\${app.edge-cell-headless-service}") private val headlessService: String,
                            @Value("\${spring.application.name}") private val appName: String) {


    // If you need to change the URL format, you change this one line.
    private fun buildEdgeDbUrl(podName: String): String {
        val ordinal = podName.substringAfterLast("-")
        return "jdbc:postgresql://payment-edge-cell-${ordinal}.payment-edge-cell-headless:5432/edge-db?options=-c%20timezone=UTC"
    }

    // -------- DataSources --------

    private fun buildDynamicEdgeUrl(): String {
        val ordinal = podName.substringAfterLast("-")
        return "jdbc:postgresql://${baseUrl}-${ordinal}.${headlessService}:5432/edge-db?options=-c%20timezone=UTC"
    }

    @Bean("outboxDataSource")
    @Primary
    @LiquibaseDataSource
    fun outboxDataSource(
        @Value("\${app.datasource.outbox.username}") user: String,
        @Value("\${app.datasource.outbox.password}") pass: String,
        @Value("\${app.datasource.outbox.connection-timeout:15000}") cTimeout: Long,
        @Value("\${app.datasource.outbox.validation-timeout:15000}") vTimeout: Long,
        @Value("\${app.datasource.outbox.idle-timeout:600000}") iTimeout: Long,
        @Value("\${app.datasource.outbox.max-lifetime:1800000}") mLife: Long,
        @Value("\${app.datasource.outbox.maximum-pool-size:20}") mPool: Int,
        openTelemetry: OpenTelemetry
    ): HikariDataSource {
        val dataSource = HikariDataSource().apply {
            poolName = "$appName-edge-outbox-pool"
            jdbcUrl = buildDynamicEdgeUrl()
            username = user
            password = pass
            connectionTimeout = cTimeout
            validationTimeout = vTimeout
            idleTimeout = iTimeout
            maxLifetime = mLife
            maximumPoolSize = mPool
        }
        dataSource.metricsTrackerFactory = HikariTelemetry.create(openTelemetry).createMetricsTrackerFactory()
        return dataSource
    }

    @Bean("maintenanceDataSource")
    fun maintenanceDataSource(
        @Value("\${app.datasource.maintenance.username}") user: String,
        @Value("\${app.datasource.maintenance.password}") pass: String,
        @Value("\${app.datasource.maintenance.connection-timeout:15000}") cTimeout: Long,
        @Value("\${app.datasource.maintenance.validation-timeout:15000}") vTimeout: Long,
        @Value("\${app.datasource.maintenance.idle-timeout:60000}") iTimeout: Long,
        @Value("\${app.datasource.maintenance.max-lifetime:1800000}") mLife: Long,
        @Value("\${app.datasource.maintenance.maximum-pool-size:1}") mPool: Int,
        @Value("\${app.datasource.maintenance.minimum-idle:0}") minIdleConns: Int,
        openTelemetry: OpenTelemetry
    ): HikariDataSource {
        val dataSource = HikariDataSource().apply {
            poolName = "$appName-edge-maintenance-pool"
            jdbcUrl = buildDynamicEdgeUrl()
            username = user
            password = pass
            connectionTimeout = cTimeout
            validationTimeout = vTimeout
            idleTimeout = iTimeout
            maxLifetime = mLife
            maximumPoolSize = mPool
            minimumIdle = minIdleConns
        }
        dataSource.metricsTrackerFactory = HikariTelemetry.create(openTelemetry).createMetricsTrackerFactory()
        return dataSource
    }

    @Bean("centralDataSource")
    fun centralDataSource(
        @Value("\${app.datasource.central.jdbc-url}") url: String,
        @Value("\${app.datasource.central.username}") user: String,
        @Value("\${app.datasource.central.password}") pass: String,
        @Value("\${app.datasource.central.connection-timeout:15000}") cTimeout: Long,
        @Value("\${app.datasource.central.validation-timeout:15000}") vTimeout: Long,
        @Value("\${app.datasource.central.idle-timeout:600000}") iTimeout: Long,
        @Value("\${app.datasource.central.max-lifetime:1800000}") mLife: Long,
        @Value("\${app.datasource.central.maximum-pool-size:10}") mPool: Int,
        openTelemetry: OpenTelemetry
    ): HikariDataSource {
        val dataSource = HikariDataSource().apply {
            poolName = "$appName-central-edge-worker-pool"
            jdbcUrl = url
            username = user
            password = pass
            connectionTimeout = cTimeout
            validationTimeout = vTimeout
            idleTimeout = iTimeout
            maxLifetime = mLife
            maximumPoolSize = mPool
        }
        dataSource.metricsTrackerFactory = HikariTelemetry.create(openTelemetry).createMetricsTrackerFactory()
        return dataSource
    }

    // -------- TxManagers --------

    @Bean("outboxTxManager")
    @Primary
    fun outboxTxManager(
        @Qualifier("outboxDataSource") ds: DataSource,
        @Value("\${db.outbox.statement-timeout-ms:30000}") stmtMs: Long,
        @Value("\${db.outbox.lock-timeout-ms:200}") lockMs: Long,
        @Value("\${db.outbox.idle-in-tx-timeout-ms:0}") idleMs: Long
    ) = DBWriterTxManager(ds, stmtMs, lockMs, idleMs).apply {
        setDefaultTimeout(60)
    }

    @Bean("maintenanceTxManager")
    fun maintenanceTxManager(@Qualifier("maintenanceDataSource") ds: DataSource) =
        DataSourceTransactionManager(ds).apply { setDefaultTimeout(5) } // DDL can be a bit longer

    @Bean("centralTxManager")
    fun centralTxManager(
        @Qualifier("centralDataSource") ds: DataSource
    ) = DataSourceTransactionManager(ds).apply { setDefaultTimeout(60) }

    // -------- JdbcTemplate for repos/DAOs that should use job pools --------

    @Bean("maintenanceJdbcTemplate")
    fun maintenanceJdbc(@Qualifier("maintenanceDataSource") ds: DataSource) = JdbcTemplate(ds)


}
