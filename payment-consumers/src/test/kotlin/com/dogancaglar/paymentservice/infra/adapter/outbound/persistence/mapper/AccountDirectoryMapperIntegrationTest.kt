package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.paymentservice.domain.model.account.AccountStatus
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException
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
 * Each test inserts the rows it needs into `accounts` (merchant, seller, ledger account), calls one
 * mapper method, and checks what comes back through the ledger_account_directory view.
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
        // Clean the table before each test (this also removes the seed) to keep tests independent
        jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE")
    }

    // ------------------------------------------------------------ findByAccountCode

    @Test
    fun `should find account profile by account code after inserting a row`() {
        insertAccount("PLATFORM_FEE_RESERVE.MERCHANT-A.EUR", "PLATFORM_FEE_RESERVE", "MERCHANT-A", null, "EUR")

        val result = accountDirectoryMapper.findByAccountCode("PLATFORM_FEE_RESERVE.MERCHANT-A.EUR")

        val expected = AccountProfile(
            accountCode = "PLATFORM_FEE_RESERVE.MERCHANT-A.EUR",
            type = LedgerAccountType.PLATFORM_FEE_RESERVE,
            masterAccountCode = "MERCHANT-A",
            subEntityId = null,
            currency = Currency("EUR"),
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
        insertAccount(
            "MERCHANT_COMMISSION_PAYABLE.MERCHANT-A.EUR",
            "MERCHANT_COMMISSION_PAYABLE",
            "MERCHANT-A",
            null,
            "EUR"
        )

        val result = accountDirectoryMapper.findByEntityAndType(
            accountType = "MERCHANT_COMMISSION_PAYABLE",
            masterAccountCode = "MERCHANT-A",
            currency = "EUR"
        )

        val expected = AccountProfile(
            accountCode = "MERCHANT_COMMISSION_PAYABLE.MERCHANT-A.EUR",
            type = LedgerAccountType.MERCHANT_COMMISSION_PAYABLE,
            masterAccountCode = "MERCHANT-A",
            subEntityId = null,
            currency = Currency("EUR"),
            status = AccountStatus.ACTIVE
        )
        assertEquals(expected, result)
    }

    @Test
    fun `should find only the account of the requested type`() {
        insertAccount("MERCHANT_DIRECT_PAYABLE.MERCHANT-A.EUR", "MERCHANT_DIRECT_PAYABLE", "MERCHANT-A", null, "EUR")
        insertAccount(
            "MERCHANT_COMMISSION_PAYABLE.MERCHANT-A.EUR",
            "MERCHANT_COMMISSION_PAYABLE",
            "MERCHANT-A",
            null,
            "EUR"
        )

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
            type = LedgerAccountType.SELLER_PAYABLE,
            masterAccountCode = "MERCHANT-A",
            subEntityId = "SELLER-A-1",
            currency = Currency("EUR"),
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

    // ----------------------------------------------------------- findAllSubEntitiesByMaster

    @Test
    fun `should list a merchant's seller accounts in natural order of the seller code, without other merchants' sellers`() {
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-10.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-10", "EUR")
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-2.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-2", "EUR")
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-1", "EUR")
        // not in the list: another merchant's seller, and the merchant's own (non-seller) account
        insertAccount("SELLER_PAYABLE.MERCHANT-B.SELLER-B-1.EUR", "SELLER_PAYABLE", "MERCHANT-B", "SELLER-B-1", "EUR")
        insertAccount(
            "MERCHANT_COMMISSION_PAYABLE.MERCHANT-A.EUR",
            "MERCHANT_COMMISSION_PAYABLE",
            "MERCHANT-A",
            null,
            "EUR"
        )

        val result = accountDirectoryMapper.findAllSubEntitiesByMaster("SELLER_PAYABLE", "MERCHANT-A")

        val sellers = mutableListOf<String?>()
        for (profile in result) {
            sellers.add(profile.subEntityId)
        }
        assertEquals(listOf("SELLER-A-1", "SELLER-A-2", "SELLER-A-10"), sellers)
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

    // ------------------------------------------------------------ findAllByMaster

    @Test
    fun `should find all accounts of one merchant and type, one per currency`() {
        insertAccount("MERCHANT_DIRECT_PAYABLE.MERCHANT-A.EUR", "MERCHANT_DIRECT_PAYABLE", "MERCHANT-A", null, "EUR")
        insertAccount("MERCHANT_DIRECT_PAYABLE.MERCHANT-A.USD", "MERCHANT_DIRECT_PAYABLE", "MERCHANT-A", null, "USD")
        insertAccount("MERCHANT_DIRECT_PAYABLE.MERCHANT-B.EUR", "MERCHANT_DIRECT_PAYABLE", "MERCHANT-B", null, "EUR")
        insertAccount(
            "MERCHANT_COMMISSION_PAYABLE.MERCHANT-A.EUR",
            "MERCHANT_COMMISSION_PAYABLE",
            "MERCHANT-A",
            null,
            "EUR"
        )

        val result = accountDirectoryMapper.findAllByMaster("MERCHANT_DIRECT_PAYABLE", "MERCHANT-A")

        assertEquals("MERCHANT_DIRECT_PAYABLE.MERCHANT-A.EUR", result[0].accountCode)
        assertEquals("MERCHANT_DIRECT_PAYABLE.MERCHANT-A.USD", result[1].accountCode)
        assertEquals(2, result.size)
    }

    @Test
    fun `should not return seller accounts for a merchant accounts lookup`() {
        insertAccount("SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.EUR", "SELLER_PAYABLE", "MERCHANT-A", "SELLER-A-1", "EUR")

        val result = accountDirectoryMapper.findAllByMaster("SELLER_PAYABLE", "MERCHANT-A")

        assertEquals(0, result.size)
    }

    // ---------------------------------------------------------- accounts table rules

    @Test
    fun `should reject a seller row that carries ledger columns`() {
        insertMerchant("MERCHANT-A", "EUR")

        val error = assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                "INSERT INTO accounts (account_code, kind, parent_code, ledger_type) VALUES ('SELLER-A-1', 'SELLER', 'MERCHANT-A', 'SELLER_PAYABLE')"
            )
        }
        assertTrue(error.message!!.contains("chk_accounts_seller"), error.message)
    }

    @Test
    fun `should reject a ledger row whose owner does not exist`() {
        val error = assertThrows(DataIntegrityViolationException::class.java) {
            insertLedger("CAPTURE_SUSPENSE.MERCHANT-X.EUR", "CAPTURE_SUSPENSE", "MERCHANT-X", "EUR")
        }
        assertTrue(error.message!!.contains("accounts_parent_code_fkey"), error.message)
    }

    // --------------------------------------------------------------- test helpers (the mapper has no insert method)

    /** The ledger account plus the rows it hangs under: its merchant, and its seller for seller accounts. */
    private fun insertAccount(
        accountCode: String,
        accountType: String,
        merchant: String,
        subEntityId: String?,
        currency: String
    ) {
        insertMerchant(merchant, currency)
        var owner = merchant
        if (subEntityId != null) {
            jdbcTemplate.update(
                "INSERT INTO accounts (account_code, kind, parent_code) VALUES (?, 'SELLER', ?) ON CONFLICT (account_code) DO NOTHING",
                subEntityId,
                merchant
            )
            owner = subEntityId
        }
        insertLedger(accountCode, accountType, owner, currency)
    }

    private fun insertMerchant(merchant: String, currency: String) {
        jdbcTemplate.update(
            """
            INSERT INTO accounts (account_code, kind, currency, is_auto_captured, is_auto_settled,
                                  platform_fee_fixed, platform_fee_bps, profile)
            VALUES (?, 'MERCHANT', ?, true, false, 0, 0, '{}'::jsonb)
            ON CONFLICT (account_code) DO NOTHING
            """.trimIndent(),
            merchant,
            currency
        )
    }

    private fun insertLedger(accountCode: String, accountType: String, owner: String, currency: String) {
        jdbcTemplate.update(
            "INSERT INTO accounts (account_code, kind, parent_code, ledger_type, currency) VALUES (?, 'LEDGER', ?, ?, ?)",
            accountCode,
            owner,
            accountType,
            currency
        )
    }
}
