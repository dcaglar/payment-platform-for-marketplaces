package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import org.apache.ibatis.session.SqlSessionFactory
import org.mybatis.spring.SqlSessionFactoryBean
import org.mybatis.spring.annotation.MapperScan
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import javax.sql.DataSource

@Configuration
@Profile("!liquibase-job")
@MapperScan(
    basePackages = ["com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper"],
    sqlSessionFactoryRef = "edgeSqlSessionFactory"
)
public class EdgeMyBatisConfig {

    @Bean(name = ["edgeSqlSessionFactory"])
    @Primary
    fun edgeSqlSessionFactory(@Qualifier("edgeDataSource") dataSource: DataSource): SqlSessionFactory {
        val sessionFactory = SqlSessionFactoryBean()
        sessionFactory.setDataSource(dataSource)
        sessionFactory.setTypeAliasesPackage("com.dogancaglar.common.db.entity")
        sessionFactory.setTypeHandlersPackage("com.dogancaglar.common.db.typehandler")
        sessionFactory.setMapperLocations(
            *PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml")
        )
        return sessionFactory.`object`!!
    }
}
