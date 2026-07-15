package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.zaxxer.hikari.HikariDataSource
import org.apache.ibatis.session.SqlSessionFactory
import org.mybatis.spring.SqlSessionFactoryBean
import org.mybatis.spring.annotation.MapperScan
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import javax.sql.DataSource
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.hikaricp.v3_0.HikariTelemetry

@Configuration
@MapperScan(
    basePackages = ["com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper"],
    sqlSessionFactoryRef = "centralOutboxSqlSessionFactory"
)
class CentralOutboxDataSourceConfig {

    // =========================================================================
    // 1. CORE TRANSACTIONAL DATASOURCE PROFILE
    // =========================================================================

    @Bean("centralOutboxDataSource")
    fun centralOutboxDataSource(
        @Value("\${app.datasource.central-outbox.jdbc-url}") jdbcUrl: String,
        @Value("\${app.datasource.central-outbox.username}") username: String,
        @Value("\${app.datasource.central-outbox.password}") password: String,
        @Value("\${app.datasource.central-outbox.pool-name:\${spring.application.name}-central-outbox-pool}") poolName: String,
        @Value("\${app.datasource.central-outbox.maximum-pool-size:5}") maxPoolSize: Int,
        @Value("\${app.datasource.central-outbox.minimum-idle:2}") minIdle: Int,
        @Value("\${app.datasource.central-outbox.connection-timeout:15000}") cTimeout: Long,
        @Value("\${app.datasource.central-outbox.validation-timeout:10000}") vTimeout: Long,
        @Value("\${app.datasource.central-outbox.idle-timeout:120000}") iTimeout: Long,
        @Value("\${app.datasource.central-outbox.max-lifetime:180000}") mLife: Long,
        openTelemetry: OpenTelemetry
    ): HikariDataSource {
        return HikariDataSource().apply {
            this.poolName = poolName
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            this.maximumPoolSize = maxPoolSize
            this.minimumIdle = minIdle
            this.maxLifetime = mLife
            this.idleTimeout = iTimeout
            this.connectionTimeout = cTimeout
            this.validationTimeout = vTimeout
        }.also {
            it.metricsTrackerFactory = HikariTelemetry.create(openTelemetry).createMetricsTrackerFactory()
        }
    }

    @Bean("centralOutboxTxManager")
    fun centralOutboxTxManager(@Qualifier("centralOutboxDataSource") ds: DataSource): DataSourceTransactionManager {
        return DataSourceTransactionManager(ds).apply { defaultTimeout = 60 }
    }



    @Bean("centralOutboxSqlSessionFactory")
    fun centralOutboxSqlSessionFactory(@Qualifier("centralOutboxDataSource") ds: DataSource): SqlSessionFactory {
        val factoryBean = SqlSessionFactoryBean()
        factoryBean.setDataSource(ds)

        // MyBatis configuration (Global settings)
        val configuration = org.apache.ibatis.session.Configuration()
        configuration.isMapUnderscoreToCamelCase = true
        factoryBean.setConfiguration(configuration)

        // NO setMapperLocations needed if files are in the same package structure!
        factoryBean.setTypeAliasesPackage("com.dogancaglar.common.db.entity")
        factoryBean.setTypeHandlersPackage("com.dogancaglar.common.db.typehandler")

        return factoryBean.`object`!!
    }

    // =========================================================================
    // 2. MAINTENANCE DATASOURCE PROFILE (Partition Checks & Schema Vacuuming)
    // =========================================================================

    @Bean("maintenanceDataSource")
    fun maintenanceDataSource(
        @Value("\${app.datasource.maintenance.jdbc-url}") jdbcUrl: String,
        @Value("\${app.datasource.maintenance.username}") username: String,
        @Value("\${app.datasource.maintenance.password}") password: String,
        @Value("\${app.datasource.maintenance.pool-name:\${spring.application.name}-central-maintenance-pool}") poolName: String,
        @Value("\${app.datasource.maintenance.maximum-pool-size:2}") maxPoolSize: Int,
        @Value("\${app.datasource.maintenance.minimum-idle:0}") minIdle: Int,
        @Value("\${app.datasource.maintenance.connection-timeout:15000}") cTimeout: Long,
        @Value("\${app.datasource.maintenance.validation-timeout:10000}") vTimeout: Long,
        @Value("\${app.datasource.maintenance.idle-timeout:120000}") iTimeout: Long,
        @Value("\${app.datasource.maintenance.max-lifetime:180000}") mLife: Long,
        openTelemetry: OpenTelemetry
    ): HikariDataSource {
        return HikariDataSource().apply {
            this.poolName = poolName
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            this.maximumPoolSize = maxPoolSize
            this.minimumIdle = minIdle
            this.maxLifetime = mLife
            this.idleTimeout = iTimeout
            this.connectionTimeout = cTimeout
            this.validationTimeout = vTimeout
        }.also {
            it.metricsTrackerFactory = HikariTelemetry.create(openTelemetry).createMetricsTrackerFactory()
        }
    }

    @Bean("maintenanceTxManager")
    fun maintenanceTxManager(@Qualifier("maintenanceDataSource") ds: DataSource): DataSourceTransactionManager {
        return DataSourceTransactionManager(ds).apply { defaultTimeout = 60 }
    }

    @Bean("maintenanceJdbcTemplate")
    fun maintenanceJdbcTemplate(@Qualifier("maintenanceDataSource") ds: DataSource): JdbcTemplate {
        return JdbcTemplate(ds)
    }
}
