package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.common.db.entity.JournalEntryEntity
import com.dogancaglar.common.db.entity.PostingEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Tests every method of LedgerMapper against a real Postgres (Testcontainers).
 * Liquibase builds the schema from the real changelog before the tests run.
 *
 * The mapper is called the way production calls it (CentralDbTransactionalFacadeAdapter):
 * insert the journal entry, and when that returns 1, insert one posting per account.
 *
 * The tests do not run inside a test transaction (NOT_SUPPORTED), so every mapper call
 * commits on its own. That is needed to see what ON CONFLICT does across two connections.
 *
 * Runs via `mvn verify -pl payment-consumers -am` (Failsafe, @Tag "integration").
 */
@Tag("integration")
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerMapperIntegrationTest {

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
    private lateinit var ledgerMapper: LedgerMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val createdAt: Instant = Instant.parse("2026-07-01T12:00:00Z")
    private val paymentId = 230392875798626304L
    private val captureTxId = 230392885156118528L

    private val suspense = "CAPTURE_SUSPENSE.MERCHANT-A.EUR"
    private val pspReceivable = "PSP_RECEIVABLE.GLOBAL.EUR"
    private val sellerPayable = "SELLER_PAYABLE.MERCHANT-A.SELLER-A-1.EUR"

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE postings, journal_entries, payment_tx CASCADE")
    }

    // ------------------------------------------------------------ insertJournalEntry

    @Test
    fun `should insert a journal entry with every field in its own column`() {
        insertPaymentTx(captureTxId)

        val inserted = ledgerMapper.insertJournalEntry(
            journal("CAPTURE:pi_1:tx_1", captureTxId, "Gross Asset Capture Pool")
        )

        assertEquals(1, inserted)
        val row = jdbcTemplate.queryForMap("SELECT * FROM journal_entries WHERE id = 'CAPTURE:pi_1:tx_1'")
        assertEquals(9001L, row["global_journal_entry_id"])
        assertEquals("CAPTURE", row["journal_type"])
        assertEquals("Gross Asset Capture Pool", row["name"])
        assertEquals(paymentId, row["payment_id"])
        assertEquals(captureTxId, row["tx_id"])
        assertEquals("2026-07-01 12:00:00", createdAtText("journal_entries", "id = 'CAPTURE:pi_1:tx_1'"))
    }

    @Test
    fun `should insert a journal entry without a tx id`() {
        val inserted = ledgerMapper.insertJournalEntry(
            journal("INTERNAL_TRANSFER:pi_1-tr_1", null, "Internal Transfer")
        )

        assertEquals(1, inserted)
        val row = jdbcTemplate.queryForMap("SELECT * FROM journal_entries WHERE id = 'INTERNAL_TRANSFER:pi_1-tr_1'")
        assertNull(row["tx_id"])
    }

    @Test
    fun `should return 0 and keep the first row when the journal id already exists`() {
        val firstInserted = ledgerMapper.insertJournalEntry(journal("CAPTURE:pi_1:tx_1", null, "first delivery"))
        val secondInserted = ledgerMapper.insertJournalEntry(journal("CAPTURE:pi_1:tx_1", null, "second delivery"))

        assertEquals(1, firstInserted)
        assertEquals(0, secondInserted)
        assertEquals(1, countRows("journal_entries"))
        assertEquals(
            "first delivery",
            jdbcTemplate.queryForObject(
                "SELECT name FROM journal_entries WHERE id = 'CAPTURE:pi_1:tx_1'",
                String::class.java
            )
        )
    }

    @Test
    fun `should reject a journal entry whose tx id has no payment_tx row`() {
        val exception = assertThrows(DataIntegrityViolationException::class.java) {
            ledgerMapper.insertJournalEntry(journal("CAPTURE:pi_1:tx_1", 777L, "Gross Asset Capture Pool"))
        }

        assertTrue(exception.message!!.contains("fk_journal_entries_tx_id"), exception.message)
        assertEquals(0, countRows("journal_entries"))
    }

    @Test
    fun `should store created_at as UTC when the JVM default timezone is not UTC`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Amsterdam"))

            ledgerMapper.insertJournalEntry(journal("CAPTURE:pi_1:tx_1", null, "Gross Asset Capture Pool"))

            assertEquals("2026-07-01 12:00:00", createdAtText("journal_entries", "id = 'CAPTURE:pi_1:tx_1'"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `second concurrent insert of the same id should wait for the first and then return 0`() {
        val executor = Executors.newSingleThreadExecutor()
        // transaction A inserts the journal and stays open: the row exists but is not committed
        val transactionA = transactionManager.getTransaction(DefaultTransactionDefinition())
        var committed = false
        try {
            val insertedByA = ledgerMapper.insertJournalEntry(journal("CAPTURE:pi_1:tx_1", null, "worker A"))
            assertEquals(1, insertedByA)

            // worker B inserts the same id on another thread, on its own connection
            val insertByB: Future<Int> = executor.submit(
                Callable {
                    ledgerMapper.insertJournalEntry(journal("CAPTURE:pi_1:tx_1", null, "worker B"))
                }
            )
            assertTrue(isStillWaiting(insertByB), "B must wait for A's transaction to end")

            transactionManager.commit(transactionA)
            committed = true

            assertEquals(0, insertByB.get(5, TimeUnit.SECONDS))
        } finally {
            if (!committed) {
                transactionManager.rollback(transactionA)
            }
            executor.shutdownNow()
        }

        assertEquals(1, countRows("journal_entries"))
        assertEquals(
            "worker A",
            jdbcTemplate.queryForObject(
                "SELECT name FROM journal_entries WHERE id = 'CAPTURE:pi_1:tx_1'",
                String::class.java
            )
        )
    }

    @Test
    fun `second concurrent insert of the same id should insert when the first rolls back`() {
        val executor = Executors.newSingleThreadExecutor()
        val transactionA = transactionManager.getTransaction(DefaultTransactionDefinition())
        var rolledBack = false
        try {
            ledgerMapper.insertJournalEntry(journal("CAPTURE:pi_1:tx_1", null, "worker A"))

            val insertByB: Future<Int> = executor.submit(
                Callable {
                    ledgerMapper.insertJournalEntry(journal("CAPTURE:pi_1:tx_1", null, "worker B"))
                }
            )
            assertTrue(isStillWaiting(insertByB), "B must wait for A's transaction to end")

            transactionManager.rollback(transactionA)
            rolledBack = true

            assertEquals(1, insertByB.get(5, TimeUnit.SECONDS))
        } finally {
            if (!rolledBack) {
                transactionManager.rollback(transactionA)
            }
            executor.shutdownNow()
        }

        assertEquals(1, countRows("journal_entries"))
        assertEquals(
            "worker B",
            jdbcTemplate.queryForObject(
                "SELECT name FROM journal_entries WHERE id = 'CAPTURE:pi_1:tx_1'",
                String::class.java
            )
        )
    }

    // ----------------------------------------------------------------- insertPosting

    @Test
    fun `should insert a posting with every field in its own column`() {
        ledgerMapper.insertJournalEntry(journal("CAPTURE:pi_1:tx_1", null, "Gross Asset Capture Pool"))

        val inserted = ledgerMapper.insertPosting(
            posting("CAPTURE:pi_1:tx_1", pspReceivable, "PSP_RECEIVABLE", 3000L, "DEBIT")
        )

        assertEquals(1, inserted)
        val row = jdbcTemplate.queryForMap("SELECT * FROM postings WHERE journal_id = 'CAPTURE:pi_1:tx_1'")
        assertNotNull(row["id"])
        assertEquals("PSP_RECEIVABLE.GLOBAL.EUR", row["account_code"])
        assertEquals("PSP_RECEIVABLE", row["account_type"])
        assertEquals(3000L, row["amount"])
        assertEquals("DEBIT", row["direction"])
        assertEquals("EUR", row["currency"])
        assertEquals("2026-07-01 12:00:00", createdAtText("postings", "journal_id = 'CAPTURE:pi_1:tx_1'"))
    }

    @Test
    fun `should reject a posting whose journal does not exist`() {
        val exception = assertThrows(DataIntegrityViolationException::class.java) {
            ledgerMapper.insertPosting(
                posting("CAPTURE:no-such-journal", pspReceivable, "PSP_RECEIVABLE", 3000L, "DEBIT")
            )
        }

        assertTrue(exception.message!!.contains("fk_postings_journal"), exception.message)
        assertEquals(0, countRows("postings"))
    }

    @Test
    fun `should return 0 and keep the first posting when the same account is posted twice in one journal`() {
        ledgerMapper.insertJournalEntry(journal("CAPTURE:pi_1:tx_1", null, "Gross Asset Capture Pool"))

        val firstInserted = ledgerMapper.insertPosting(
            posting("CAPTURE:pi_1:tx_1", suspense, "CAPTURE_SUSPENSE", 3000L, "CREDIT")
        )
        val secondInserted = ledgerMapper.insertPosting(
            posting("CAPTURE:pi_1:tx_1", suspense, "CAPTURE_SUSPENSE", 9999L, "DEBIT")
        )

        assertEquals(1, firstInserted)
        assertEquals(0, secondInserted)
        val row = jdbcTemplate.queryForMap("SELECT * FROM postings WHERE account_code = ?", suspense)
        assertEquals(3000L, row["amount"])
        assertEquals("CREDIT", row["direction"])
    }

    @Test
    fun `should insert the same account in two different journals`() {
        ledgerMapper.insertJournalEntry(journal("INTERNAL_TRANSFER:pi_1-tr_1", null, "Internal Transfer"))
        ledgerMapper.insertJournalEntry(journal("INTERNAL_TRANSFER:pi_1-tr_2", null, "Internal Transfer"))

        val firstInserted = ledgerMapper.insertPosting(
            posting("INTERNAL_TRANSFER:pi_1-tr_1", sellerPayable, "SELLER_PAYABLE", 1400L, "CREDIT")
        )
        val secondInserted = ledgerMapper.insertPosting(
            posting("INTERNAL_TRANSFER:pi_1-tr_2", sellerPayable, "SELLER_PAYABLE", 1400L, "CREDIT")
        )

        assertEquals(1, firstInserted)
        assertEquals(1, secondInserted)
        assertEquals(2, countRows("postings"))
    }

    // ------------------------------------------- journal and postings used together

    @Test
    fun `second delivery of the same journal should insert nothing`() {
        // first delivery, in the order CentralDbTransactionalFacadeAdapter uses
        val firstJournalInserted = ledgerMapper.insertJournalEntry(
            journal("CAPTURE:pi_1:tx_1", null, "Gross Asset Capture Pool")
        )
        ledgerMapper.insertPosting(posting("CAPTURE:pi_1:tx_1", pspReceivable, "PSP_RECEIVABLE", 3000L, "DEBIT"))
        ledgerMapper.insertPosting(posting("CAPTURE:pi_1:tx_1", suspense, "CAPTURE_SUSPENSE", 3000L, "CREDIT"))

        // second delivery of the same event: the adapter stops at 0 and never reaches the postings
        val secondJournalInserted = ledgerMapper.insertJournalEntry(
            journal("CAPTURE:pi_1:tx_1", null, "Gross Asset Capture Pool")
        )

        assertEquals(1, firstJournalInserted)
        assertEquals(0, secondJournalInserted)
        assertEquals(1, countRows("journal_entries"))
        assertEquals(2, countRows("postings"))
    }

    @Test
    fun `journal and postings written in one transaction should disappear together on rollback`() {
        val transaction = transactionManager.getTransaction(DefaultTransactionDefinition())
        ledgerMapper.insertJournalEntry(journal("CAPTURE:pi_1:tx_1", null, "Gross Asset Capture Pool"))
        ledgerMapper.insertPosting(posting("CAPTURE:pi_1:tx_1", pspReceivable, "PSP_RECEIVABLE", 3000L, "DEBIT"))
        // inside the transaction both rows are there
        assertEquals(1, countRows("journal_entries"))
        assertEquals(1, countRows("postings"))

        transactionManager.rollback(transaction)

        assertEquals(0, countRows("journal_entries"))
        assertEquals(0, countRows("postings"))
    }

    @Test
    fun `deleting a payment_tx row should remove its journal and postings`() {
        // Both foreign keys are ON DELETE CASCADE
        insertPaymentTx(captureTxId)
        ledgerMapper.insertJournalEntry(journal("CAPTURE:pi_1:tx_1", captureTxId, "Gross Asset Capture Pool"))
        ledgerMapper.insertPosting(posting("CAPTURE:pi_1:tx_1", pspReceivable, "PSP_RECEIVABLE", 3000L, "DEBIT"))
        ledgerMapper.insertPosting(posting("CAPTURE:pi_1:tx_1", suspense, "CAPTURE_SUSPENSE", 3000L, "CREDIT"))
        assertEquals(1, countRows("journal_entries"))
        assertEquals(2, countRows("postings"))

        jdbcTemplate.update("DELETE FROM payment_tx WHERE tx_id = ?", captureTxId)

        assertEquals(0, countRows("journal_entries"))
        assertEquals(0, countRows("postings"))
    }

    // --------------------------------------------------------------- test helpers

    private fun journal(id: String, txId: Long?, name: String): JournalEntryEntity {
        var journalType = "CAPTURE"
        if (id.startsWith("INTERNAL_TRANSFER")) {
            journalType = "INTERNAL_TRANSFER"
        }
        return JournalEntryEntity(
            id = id,
            globalJournalEntryId = 9001L,
            journalType = journalType,
            name = name,
            paymentId = paymentId,
            txId = txId,
            createdAt = createdAt
        )
    }

    private fun posting(journalId: String, accountCode: String, accountType: String, amount: Long, direction: String): PostingEntity {
        return PostingEntity(
            journalId = journalId,
            accountCode = accountCode,
            accountType = accountType,
            amount = amount,
            direction = direction,
            currency = "EUR",
            createdAt = createdAt
        )
    }

    private fun insertPaymentTx(txId: Long) {
        jdbcTemplate.update(
            """
            INSERT INTO payment_tx (tx_id, tx_type, payment_intent_id, payment_id, status, amount_value, amount_currency)
            VALUES (?, 'CAPTURE', 230392644730224640, ?, 'SUCCESS', 3000, 'EUR')
            """.trimIndent(),
            txId,
            paymentId
        )
    }

    private fun countRows(table: String): Int {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)!!
    }

    private fun createdAtText(table: String, where: String): String {
        return jdbcTemplate.queryForObject("SELECT created_at::text FROM $table WHERE $where", String::class.java)!!
    }

    /** True when the call has not returned within 700 ms, which means it is blocked on a lock. */
    private fun isStillWaiting(future: Future<Int>): Boolean {
        try {
            future.get(700, TimeUnit.MILLISECONDS)
            return false
        } catch (e: TimeoutException) {
            return true
        }
    }
}
