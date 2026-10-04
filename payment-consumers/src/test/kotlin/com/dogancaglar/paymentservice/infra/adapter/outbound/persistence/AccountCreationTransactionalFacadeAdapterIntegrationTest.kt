package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.paymentservice.application.command.CreateAccountCommand
import com.dogancaglar.paymentservice.application.service.CreateAccountService
import com.dogancaglar.paymentservice.domain.model.account.Address
import com.dogancaglar.paymentservice.domain.model.account.PlatformFee
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.AccountDirectoryMapper
import com.dogancaglar.paymentservice.infra.adapter.outbound.serialization.JacksonConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Account creation through CreateAccountService and the real facade, mappers and Postgres
 * (schema + seed from the real Liquibase changelog). Each call commits on its own (NOT_SUPPORTED),
 * like the consumer does.
 *
 * Runs via `mvn verify -pl payment-consumers -am` (Failsafe, @Tag "integration").
 */
@Tag("integration")
@Testcontainers
@MybatisTest
@Import(AccountCreationTransactionalFacadeAdapter::class, JacksonConfig::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountCreationTransactionalFacadeAdapterIntegrationTest {

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

            // The real changelog (charts/central-db/db) incl. the seed, copied to the test classpath by the pom
            registry.add("spring.liquibase.enabled") { true }
            registry.add("spring.liquibase.change-log") { "classpath:db/changelog/changelog.central.xml" }

            // Same MyBatis setting as production (PaymentConsumerDataSourceConfig)
            registry.add("mybatis.configuration.map-underscore-to-camel-case") { true }
        }
    }

    @Autowired
    private lateinit var facade: AccountCreationTransactionalFacadeAdapter

    @Autowired
    private lateinit var accountDirectoryMapper: AccountDirectoryMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var service: CreateAccountService

    @BeforeEach
    fun setUp() {
        service = CreateAccountService(facade)
        // remove what earlier tests created; the seed (MARKETPLACE-1..5, GLOBAL) stays
        jdbcTemplate.update("DELETE FROM accounts WHERE kind = 'LEDGER' AND account_code LIKE '%.NEW-%'")
        jdbcTemplate.update("DELETE FROM accounts WHERE kind = 'LEDGER' AND currency = 'USD'")
        jdbcTemplate.update("DELETE FROM accounts WHERE kind = 'SELLER' AND parent_code LIKE 'NEW-%'")
        jdbcTemplate.update("DELETE FROM accounts WHERE kind = 'MERCHANT' AND account_code LIKE 'NEW-%'")
    }

    @Test
    fun `creates the merchant, its sellers and all their ledger accounts`() {
        service.create(command("NEW-1", listOf("NEW-1-S1", "NEW-1-S2")))

        assertEquals(1, count("SELECT count(*) FROM accounts WHERE account_code = 'NEW-1' AND kind = 'MERCHANT'"))
        assertEquals(2, count("SELECT count(*) FROM accounts WHERE kind = 'SELLER' AND parent_code = 'NEW-1'"))
        // 6 merchant-level ledger accounts + 1 SELLER_PAYABLE per seller
        assertEquals(8, count("SELECT count(*) FROM ledger_account_directory WHERE master_account_code = 'NEW-1'"))

        // the payment flow's lookups find them
        val suspense = accountDirectoryMapper.findByEntityAndType("CAPTURE_SUSPENSE", "NEW-1", "EUR")
        assertEquals("CAPTURE_SUSPENSE.NEW-1.EUR", suspense?.accountCode)
        val sellerPayable = accountDirectoryMapper.findBySubEntity("SELLER_PAYABLE", "NEW-1", "NEW-1-S2", "EUR")
        assertEquals("SELLER_PAYABLE.NEW-1.NEW-1-S2.EUR", sellerPayable?.accountCode)

        // merchant settings are stored as columns, the profile as JSON
        val row = jdbcTemplate.queryForMap("SELECT * FROM accounts WHERE account_code = 'NEW-1'")
        assertEquals(true, row["is_auto_captured"])
        assertEquals(false, row["is_auto_settled"])
        assertEquals(30L, row["platform_fee_fixed"])
        assertEquals(150, row["platform_fee_bps"])
        assertEquals(
            "New Merchant B.V.",
            jdbcTemplate.queryForObject(
                "SELECT profile->>'legalName' FROM accounts WHERE account_code = 'NEW-1'",
                String::class.java
            )
        )
        assertEquals(
            "NL",
            jdbcTemplate.queryForObject(
                "SELECT profile->'address'->>'country' FROM accounts WHERE account_code = 'NEW-1'",
                String::class.java
            )
        )
    }

    @Test
    fun `creating a merchant that already exists writes nothing (idempotent)`() {
        service.create(command("NEW-2", listOf("NEW-2-S1")))

        // a repeat (here even with an extra seller): no error, no change
        service.create(command("NEW-2", listOf("NEW-2-S1", "NEW-2-S9")))

        assertEquals(1, count("SELECT count(*) FROM accounts WHERE kind = 'SELLER' AND parent_code = 'NEW-2'"))
        assertEquals(0, count("SELECT count(*) FROM accounts WHERE account_code = 'NEW-2-S9'"))
    }

    @Test
    fun `a seller code taken by another merchant rolls the whole creation back`() {
        // SELLER-5-1 belongs to MARKETPLACE-5 in the seed
        assertThrows(DuplicateKeyException::class.java) {
            service.create(command("NEW-3", listOf("NEW-3-S1", "SELLER-5-1")))
        }

        assertEquals(0, count("SELECT count(*) FROM accounts WHERE account_code = 'NEW-3'"))
        assertEquals(0, count("SELECT count(*) FROM accounts WHERE account_code LIKE '%NEW-3%'"))
        assertEquals(
            "MARKETPLACE-5",
            jdbcTemplate.queryForObject(
                "SELECT parent_code FROM accounts WHERE account_code = 'SELLER-5-1'",
                String::class.java
            )
        )
    }

    @Test
    fun `the first merchant in a new currency also gets the platform ledger accounts for it`() {
        assertEquals(
            0,
            count(
                "SELECT count(*) FROM ledger_account_directory WHERE master_account_code = 'GLOBAL' AND currency = 'USD'"
            )
        )

        service.create(command("NEW-4", listOf("NEW-4-S1"), currency = "USD"))

        assertEquals(
            4,
            count(
                "SELECT count(*) FROM ledger_account_directory WHERE master_account_code = 'GLOBAL' AND currency = 'USD'"
            )
        )
        assertNotNull(accountDirectoryMapper.findByEntityAndType("PSP_RECEIVABLE", "GLOBAL", "USD"))
    }

    private fun command(merchant: String, sellers: List<String>, currency: String = "EUR"): CreateAccountCommand {
        val cur = Currency(currency)
        return CreateAccountCommand(
            merchantAccountCode = merchant,
            legalName = "New Merchant B.V.",
            address = Address.of("Damrak 9", null, "Amsterdam", "1012 LG", "NL"),
            industry = "5399",
            currency = cur,
            platformFee = PlatformFee.of(Amount.of(30, cur), 150),
            isAutoCaptured = true,
            isAutoSettled = false,
            sellerAccountCodes = sellers
        )
    }

    private fun count(sql: String): Int = jdbcTemplate.queryForObject(sql, Int::class.java)!!
}
