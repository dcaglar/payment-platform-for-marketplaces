package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.zaxxer.hikari.HikariDataSource
import org.apache.ibatis.session.SqlSessionFactory
import org.mybatis.spring.SqlSessionFactoryBean
import org.mybatis.spring.annotation.MapperScan
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import javax.sql.DataSource
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.hikaricp.v3_0.HikariTelemetry
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

@Configuration
@MapperScan(
    basePackages = ["com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.snapshotmapper"],
    sqlSessionFactoryRef = "snapshotSqlSessionFactory"
)
class AccountSnapshotJobDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.account-snapshot-job")
    fun snapshotDataSourceProperties(): DataSourceProperties {
        return DataSourceProperties()
    }

    @Bean
    @ConfigurationProperties("spring.datasource.account-snapshot-job.hikari")
    fun snapshotDataSource(
        @Qualifier("snapshotDataSourceProperties") properties: DataSourceProperties,
        openTelemetry: OpenTelemetry
    ): HikariDataSource {
        val dataSource = properties.initializeDataSourceBuilder()
            .type(HikariDataSource::class.java).build()
        dataSource.metricsTrackerFactory = HikariTelemetry.create(openTelemetry).createMetricsTrackerFactory()
        return dataSource
    }

    @Bean
    fun snapshotSqlSessionFactory(@Qualifier("snapshotDataSource") snapshotDataSource: DataSource): SqlSessionFactory {
        val factoryBean = SqlSessionFactoryBean()
        factoryBean.setDataSource(snapshotDataSource)
        
        val configuration = org.apache.ibatis.session.Configuration()
        configuration.isMapUnderscoreToCamelCase = true
        factoryBean.setConfiguration(configuration)

        val resolver = PathMatchingResourcePatternResolver()
        factoryBean.setMapperLocations(*resolver.getResources("classpath*:mapper/snapshotmapper/*.xml"))
        factoryBean.setTypeAliasesPackage("com.dogancaglar.common.db.entity")
        factoryBean.setTypeHandlersPackage("com.dogancaglar.common.db.typehandler")
        
        return factoryBean.`object`!!
    }

    @Bean("snapshotTransactionManager")
    fun snapshotTransactionManager(@Qualifier("snapshotDataSource") snapshotDataSource: DataSource): DataSourceTransactionManager {
        return DataSourceTransactionManager(snapshotDataSource)
    }
}
