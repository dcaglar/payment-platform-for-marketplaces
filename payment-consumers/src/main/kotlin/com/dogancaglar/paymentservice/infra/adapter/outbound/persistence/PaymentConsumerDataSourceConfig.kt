package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.zaxxer.hikari.HikariDataSource
import org.apache.ibatis.session.SqlSessionFactory
import org.mybatis.spring.SqlSessionFactoryBean
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import javax.sql.DataSource
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.hikaricp.v3_0.HikariTelemetry
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

@Configuration
@MapperScan(
    basePackages = ["com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper"],
    sqlSessionFactoryRef = "primarySqlSessionFactory"
)
class PaymentConsumerDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.payment-consumers")
    fun primaryDataSourceProperties(): DataSourceProperties {
        return DataSourceProperties()
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.payment-consumers.hikari")
    fun primaryDataSource(openTelemetry: OpenTelemetry): HikariDataSource {
        val dataSource = primaryDataSourceProperties().initializeDataSourceBuilder()
            .type(HikariDataSource::class.java).build()
        dataSource.metricsTrackerFactory = HikariTelemetry.create(openTelemetry).createMetricsTrackerFactory()
        return dataSource
    }

    @Bean
    @Primary
    fun primarySqlSessionFactory(primaryDataSource: DataSource): SqlSessionFactory {
        val factoryBean = SqlSessionFactoryBean()
        factoryBean.setDataSource(primaryDataSource)
        
        val configuration = org.apache.ibatis.session.Configuration()
        configuration.isMapUnderscoreToCamelCase = true
        factoryBean.setConfiguration(configuration)

        val resolver = PathMatchingResourcePatternResolver()
        // Crucial: Only scan the main mapper XMLs, ignoring snapshotmapper XMLs to avoid duplicates
        val resources = resolver.getResources("classpath*:mapper/**/*.xml")
            .filter { !it.url.path.contains("/snapshotmapper/") }
            .toTypedArray()

        factoryBean.setMapperLocations(*resources)
        factoryBean.setTypeAliasesPackage("com.dogancaglar.common.db.entity")
        factoryBean.setTypeHandlersPackage("com.dogancaglar.common.db.typehandler")
        
        return factoryBean.`object`!!
    }

    @Bean
    @Primary
    fun primaryTransactionManager(primaryDataSource: DataSource): DataSourceTransactionManager {
        return DataSourceTransactionManager(primaryDataSource)
    }
}
