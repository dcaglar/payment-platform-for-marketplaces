package com.dogancaglar.common.db

import com.dogancaglar.common.db.typehandler.IdempotencyStatusTypeHandler
import com.dogancaglar.common.db.typehandler.InstantTypeHandler
import com.dogancaglar.common.db.typehandler.UUIDTypeHandler
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for shared MyBatis TypeHandlers.
 *
 * Consumers of this module get all four TypeHandlers registered automatically.
 * Each runtime module still owns its own DataSource configuration, pool tuning,
 * Liquibase migrations, and @Mapper interfaces — this module only provides
 * the reusable type mapping glue.
 *
 * The mybatis.type-handlers-package property in each module's application.yml
 * should be updated to point to: com.dogancaglar.common.db.typehandler
 */
@AutoConfiguration
class CommonDbAutoConfiguration {

    @Bean
    fun instantTypeHandler() = InstantTypeHandler()

    @Bean
    fun uuidTypeHandler() = UUIDTypeHandler()

    @Bean
    fun idempotencyStatusTypeHandler() = IdempotencyStatusTypeHandler()
}
