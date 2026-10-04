package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.paymentservice.infra.adapter.outbound.serialization.JacksonConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * MerchantAccountRepositoryAdapter against the real `accounts` table (schema from the real Liquibase changelog).
 *
 * Runs via `mvn verify -pl payment-consumers -am` (Failsafe, @Tag "integration").
 */
@Tag("integration")
@Testcontainers
@MybatisTest
@Import(MerchantAccountRepositoryAdapter::class, JacksonConfig::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MerchantAccountRepositoryAdapterIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
            .withDatabaseName("central_db")
            .withUsername("test_user")
            .withPassword("test_password")

        @JvmStatic
        @DynamicPropertySource
        fun registerDynamicProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.liquibase.enabled") { true }
            registry.add("spring.liquibase.change-log") { "classpath:db/changelog/changelog.central.xml" }
            registry.add("mybatis.configuration.map-underscore-to-camel-case") { true }
        }
    }

    @Autowired
    private lateinit var repository: MerchantAccountRepositoryAdapter

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `a merchant row is read back with its settings and profile`() {
        jdbcTemplate.update(
            """
            INSERT INTO accounts (account_code, kind, status, parent_code, ledger_type, currency,
                                  is_auto_captured, is_auto_settled, platform_fee_fixed, platform_fee_bps, profile)
            VALUES ('MERCHANT-READ-1', 'MERCHANT', 'ACTIVE', NULL, NULL, 'EUR', true, true, 25, 150,
                    '{"legalName":"Read One BV","address":{"line1":"Main 1","line2":null,"city":"Amsterdam","postalCode":"1011AA","country":"NL"},"industry":"retail"}'::jsonb)
            """
        )

        val merchant = repository.findByCode("MERCHANT-READ-1")!!

        assertEquals("MERCHANT-READ-1", merchant.accountCode)
        assertEquals(true, merchant.isAutoCaptured)
        assertEquals(true, merchant.isAutoSettled)
        assertEquals("EUR", merchant.currency.currencyCode)
        assertEquals(25L, merchant.platformFee.fixed.quantity)
        assertEquals(150, merchant.platformFee.basisPoints)
        assertEquals("Read One BV", merchant.legalName)
        assertEquals("Amsterdam", merchant.address.city)
        assertNull(merchant.address.line2)
        assertEquals("retail", merchant.industry)
    }

    @Test
    fun `an unknown merchant is not found, and neither is a seller with that code`() {
        jdbcTemplate.update(
            """
            INSERT INTO accounts (account_code, kind, status, parent_code, ledger_type, currency,
                                  is_auto_captured, is_auto_settled, platform_fee_fixed, platform_fee_bps, profile)
            VALUES ('MERCHANT-READ-2', 'MERCHANT', 'ACTIVE', NULL, NULL, 'EUR', true, false, 0, 0,
                    '{"legalName":"Read Two BV","address":{"line1":"Main 2","line2":null,"city":"Utrecht","postalCode":"3511AA","country":"NL"},"industry":"retail"}'::jsonb)
            """
        )
        jdbcTemplate.update(
            """
            INSERT INTO accounts (account_code, kind, status, parent_code)
            VALUES ('SELLER-READ-2-1', 'SELLER', 'ACTIVE', 'MERCHANT-READ-2')
            """
        )

        assertNull(repository.findByCode("MERCHANT-NOT-THERE"))
        assertNull(repository.findByCode("SELLER-READ-2-1"))
    }
}
