package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountCategory
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.AccountStatus
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Tests every method of AccountDirectoryMapper against a real Postgres (Testcontainers).
 * Liquibase builds the schema from the real changelog before the tests run.
 *
 * Each test inserts the rows it needs, calls one mapper method, and checks what comes back.
 *
 * Runs via `mvn verify -pl payment-consumers -am` (Failsafe, @Tag "integration").
 */
@Tag("integration")
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountDirectoryMapperIntegrationTest {

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

            // The real changelog (charts/central-db/db), copied to the test classpath by the pom
            registry.add("spring.liquibase.enabled") { true }
            registry.add("spring.liquibase.change-log") { "classpath:db/changelog/changelog.central.xml" }

            // Same MyBatis setting as production (PaymentConsumerDataSourceConfig)
            registry.add("mybatis.configuration.map-underscore-to-camel-case") { true }
        }
    }

    @Autowired
    private lateinit var accountDirectoryMapper: AccountDirectoryMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun cleanDatabase() {
        // Clean the table before each test to keep tests independent from each other
        jdbcTemplate.execute("TRUNCATE TABLE account_directory CASCADE")
    }

    // ------------------------------------------------------------ findByAccountCode

    @Test
    fun `should find account profile by account code after inserting a row`() {
        insertAccount("PLATFORM_FEE_RESERVE.MERCHANT-A.EUR", "PLATFORM_FEE_RESERVE", "MERCHANT-A", null, "EUR")

        val result = accountDirectoryMapper.findByAccountCode("PLATFORM_FEE_RESERVE.MERCHANT-A.EUR")

        val expected = AccountProfile(
            accountCode = "PLATFORM_FEE_RESERVE.MERCHANT-A.EUR",
            type = AccountType.PLATFORM_FEE_RESERVE,
            masterAccountCode = "MERCHANT-A",
            subEntityId = null,
            currency = Currency("EUR"),
            category = AccountCategory.LIABILITY,
            country = "NL",
            status = AccountStatus.ACTIVE
        )
        assertEquals(expected, result)
    }

    @Test
    fun `should return null for an account code that does not exist`() {
        insertAccount("PLATFORM_FEE_RESERVE.MERCHANT-A.EUR", "PLATFORM_FEE_RESERVE", "MERCHANT-A", null, "EUR")

        val result = accountDirectoryMapper.findByAccountCode("PLATFORM_FEE_RESERVE.MERCHANT-B.EUR")

        assertNull(result)
    }

    // ---------------------------------------------------------- findByEntityAndType

    @Test
    fun `should find merchant account by type, merchant and currency`() {
        insertAccount("MERCHANT_COMMISSION_PAYABLE.MERCHANT-A.EUR", "MERCHANT_COMMISSION_PAYABLE", "MERCHANT-A", null, "EUR")

        val result = accountDirectoryMapper.findByEntityAndType(
            accountType = "MERCHANT_COMMISSION_PAYABLE",
            masterAccountCode = "MERCHANT-A",
            currency = "EUR"
        )

        val expected = AccountProfile(
            accountCode = "MERCHANT_COMMISSION_PAYABLE.MERCHANT-A.EUR",
            type = AccountType.MERCHANT_COMMISSION_PAYABLE,
            masterAccountCode = "MERCHANT-A",
            subEntityId = null,
            currency = Currency("EUR"),
            category = AccountCategory.LIABILITY,
            country = "NL",
            status = AccountStatus.ACTIVE
        )
        assertEquals(expected, result)
    }

    @Test
    fun `should find only the account of the requested type`() {
        insertAccount("MERCHANT_DIRECT_PAYABLE.MERCHANT-A.EUR", "MERCHANT_DIRECT_PAYABLE", "MERCHANT-A", null, "EUR")
        insertAccount("MERCHANT_COMMISSION_PAYABLE.MERCHANT-A.EUR", "MERCHANT_COMMISSION_PAYABLE", "MERCHANT-A", null, "EUR")

        val result = accountDirectoryMapper.findByEntityAndType("MERCHANT_DIRECT_PAYABLE", "MERCHANT-A", "EUR")

        assertEquals("MERCHANT_DIRECT_PAYABLE.MERCHANT-A.EUR", result?.accountCode)
    }

    @Test
    fun `should find only the account of the requested merchant`() {
        insertAccount("CAPTURE_SUSPENSE.MERCHANT-A.EUR", "CAPTURE_SUSPENSE", "MERCHANT-A", null, "EUR")
        insertAccount("CAPTURE_SUSPENSE.MERCHANT-B.EUR", "CAPTURE_SUSPENSE", "MERCHANT-B", null, "EUR")

        val result = accountDirectoryMapper.findByEntityAndType("CAPTURE_SUSPENSE", "MERCHANT-B", "EUR")

        assertEquals("CAPTURE_SUSPENSE.MERCHANT-B.EUR", result?.accountCode)
    }

    @Test
    fun `should find only the account of the requested currency`() {
        insertAccount("CAPTURE_SUSPENSE.MERCHANT-A.EUR", "CAPTURE_SUSPENSE", "MERCHANT-A", null, "EUR")
        insertAccount("CAPTURE_SUSPENSE.MERCHANT-A.USD", "CAPTURE_SUSPENSE", "MERCHANT-A", null, "USD")

        val result = accountDirectoryMapper.findByEntityAndType("CAPTURE_SUSPENSE", "MERCHANT-A", "USD")

        assertEquals("CAPTURE_SUSPENSE.MERCHANT-A.USD", result?.accountCode)
        assertEquals(Currency("USD"), result?.currency)
    }

    @Test
    fun `should return null when the merchant has no account of that type`() {
        insertAccount("CAPTURE_SUSPENSE.MERCHANT-A.EUR", "CAPTURE_SUSPENSE", "MERCHANT-A", null, "EUR")

        val result = accountDirectoryMapper.findByEntityAndType("PLATFORM_FEE_RESERVE", "MERCHANT-A", "EUR")

        assertNull(result)
    }

    @Test
    fun `should not return a seller account for a merchant level lookup`() {
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-1", "EUR")

        val result = accountDirectoryMapper.findByEntityAndType("SELLER_PAYABLE", "MERCHANT-A", "EUR")

        assertNull(result)
    }

    // -------------------------------------------------------------- findBySubEntity

    @Test
    fun `should find account by sub entity and currency`() {
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-1", "EUR")

        val result = accountDirectoryMapper.findBySubEntity(
            accountType = "SELLER_PAYABLE",
            masterAccountCode = "MERCHANT-A",
            subEntityId = "SELLER-A-1",
            currency = "EUR"
        )

        val expected = AccountProfile(
            accountCode = "SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.EUR",
            type = AccountType.SELLER_PAYABLE,
            masterAccountCode = "MERCHANT-A",
            subEntityId = "SELLER-A-1",
            currency = Currency("EUR"),
            category = AccountCategory.LIABILITY,
            country = "NL",
            status = AccountStatus.ACTIVE
        )
        assertEquals(expected, result)
    }

    @Test
    fun `should find only the account of the requested sub entity`() {
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-1", "EUR")
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-2.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-2", "EUR")

        val result = accountDirectoryMapper.findBySubEntity("SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-2", "EUR")

        assertEquals("SELLER_PAYABLE.MERCHANT-A.SELLER-A-2.EUR", result?.accountCode)
    }

    @Test
    fun `should return null when the sub entity belongs to another merchant`() {
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-1", "EUR")

        val result = accountDirectoryMapper.findBySubEntity("SELLER_PAYABLE", "MERCHANT-B", "SELLER-A-1", "EUR")

        assertNull(result)
    }

    @Test
    fun `should return null when the sub entity has no account in that currency`() {
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-1", "EUR")

        val result = accountDirectoryMapper.findBySubEntity("SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-1", "USD")

        assertNull(result)
    }

    // ----------------------------------------------------------- findAllBySubEntity

    @Test
    fun `should find all accounts belonging to a sub entity`() {
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.USD", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-1", "USD")
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-1", "EUR")
        // another seller: must not be returned
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-2.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-2", "EUR")

        val results = accountDirectoryMapper.findAllBySubEntity(
            accountType = "SELLER_PAYABLE",
            subEntityId = "SELLER-A-1"
        )

        assertEquals(2, results.size)
        // ordered by currency
        assertEquals("SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.EUR", results[0].accountCode)
        assertEquals("SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.USD", results[1].accountCode)
    }

    @Test
    fun `should return an empty list for a sub entity without accounts`() {
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-1", "EUR")

        val results = accountDirectoryMapper.findAllBySubEntity("SELLER_PAYABLE", "SELLER-A-9")

        assertEquals(0, results.size)
    }

    // --------------------------------------------------------------- test helper

    /** Inserts a test record directly using JdbcTemplate. The mapper has no insert method. */
    private fun insertAccount(accountCode: String, accountType: String, merchant: String, subEntityId: String?, currency: String) {
        jdbcTemplate.update(
            """
            INSERT INTO account_directory (
                account_code, account_type, master_account_code, sub_entity_id, currency, category, country, status
            ) VALUES (?, ?, ?, ?, ?, 'LIABILITY', 'NL', 'ACTIVE')
            """.trimIndent(),
            accountCode, accountType, merchant, subEntityId, currency
        )
    }
}
