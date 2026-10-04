package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.common.db.entity.PaymentTxEntity
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
 * Tests every method of PaymentTxMapper against a real Postgres (Testcontainers).
 * Liquibase builds the schema from the real changelog before the tests run.
 *
 * The mapper is called the way production calls it: PaymentTxAdapter and
 * CentralDbTransactionalFacadeAdapter call upsert for a new tx AND for every later state
 * change of that tx; PaymentTxAdapter.findByPaymentId reads them back.
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
class PaymentTxMapperIntegrationTest {

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
    private lateinit var paymentTxMapper: PaymentTxMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val paymentId = 230392875798626304L
    private val otherPaymentId = 230392875798626999L
    private val paymentIntentId = 230392644730224640L
    private val authTxId = 100L
    private val captureTxId = 200L

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE postings, journal_entries, payment_tx CASCADE")
    }

    // ------------------------------------------------------------------------ upsert

    @Test
    fun `should insert a new tx with every field in its own column`() {
        paymentTxMapper.upsert(authorization())

        paymentTxMapper.upsert(capture("PENDING", "UNMATCHED", null, null))

        val row = jdbcTemplate.queryForMap("SELECT * FROM payment_tx WHERE tx_id = ?", captureTxId)
        assertEquals("CAPTURE", row["tx_type"])
        assertEquals(paymentId, row["payment_id"])
        assertEquals(paymentIntentId, row["payment_intent_id"])
        assertEquals(authTxId, row["parent_tx_id"])
        assertEquals("psp-capture-ref", row["acquirer_reference"])
        assertEquals(3000L, row["amount_value"])
        assertEquals("EUR", row["amount_currency"])
        assertEquals("PENDING", row["status"])
        assertEquals("UNMATCHED", row["settle_status"])
        assertNull(row["acquirer_batch_ref"])
        assertNull(row["settled_amount_value"])
    }

    @Test
    fun `should update status and the settlement columns when the tx id already exists`() {
        paymentTxMapper.upsert(authorization())
        paymentTxMapper.upsert(capture("PENDING", "UNMATCHED", null, null))

        paymentTxMapper.upsert(capture("SUCCESS", "MATCHED", "BATCH-42", 2955L))

        assertEquals(2, countRows())
        val row = jdbcTemplate.queryForMap("SELECT * FROM payment_tx WHERE tx_id = ?", captureTxId)
        assertEquals("SUCCESS", row["status"])
        assertEquals("MATCHED", row["settle_status"])
        assertEquals("BATCH-42", row["acquirer_batch_ref"])
        assertEquals(2955L, row["settled_amount_value"])
    }

    @Test
    fun `should keep type, payment, parent, reference, amount and currency as first written`() {
        paymentTxMapper.upsert(authorization())
        paymentTxMapper.upsert(capture("PENDING", "UNMATCHED", null, null))

        val changed = PaymentTxEntity(
            txId = captureTxId,
            txType = "REFUND",
            paymentId = otherPaymentId,
            paymentIntentId = 1L,
            parentTxId = null,
            acquirerReference = "another-ref",
            amountValue = 1L,
            amountCurrency = "USD",
            status = "SUCCESS",
            settleStatus = "UNMATCHED",
            acquirerBatchRef = null,
            settledAmountValue = null
        )
        paymentTxMapper.upsert(changed)

        val row = jdbcTemplate.queryForMap("SELECT * FROM payment_tx WHERE tx_id = ?", captureTxId)
        assertEquals("CAPTURE", row["tx_type"])
        assertEquals(paymentId, row["payment_id"])
        assertEquals(paymentIntentId, row["payment_intent_id"])
        assertEquals(authTxId, row["parent_tx_id"])
        assertEquals("psp-capture-ref", row["acquirer_reference"])
        assertEquals(3000L, row["amount_value"])
        assertEquals("EUR", row["amount_currency"])
        assertEquals("SUCCESS", row["status"])
    }

    @Test
    fun `should move SUCCESS back to PENDING on a stale upsert because there is no status guard`() {
        // ON CONFLICT DO UPDATE has no WHERE clause and the table has no version column,
        // so the row always takes the values of the last upsert.
        paymentTxMapper.upsert(authorization())
        paymentTxMapper.upsert(capture("SUCCESS", "MATCHED", null, null))

        paymentTxMapper.upsert(capture("PENDING", "UNMATCHED", null, null))

        val row = jdbcTemplate.queryForMap("SELECT * FROM payment_tx WHERE tx_id = ?", captureTxId)
        assertEquals("PENDING", row["status"])
        assertEquals("UNMATCHED", row["settle_status"])
    }

    @Test
    fun `should reject a tx whose parent tx id does not exist`() {
        val exception = assertThrows(DataIntegrityViolationException::class.java) {
            paymentTxMapper.upsert(capture("PENDING", "UNMATCHED", null, null))
        }

        assertTrue(exception.message!!.contains("fk_payment_tx_parent"), exception.message)
        assertEquals(0, countRows())
    }

    @Test
    fun `should reject values outside the allowed sets`() {
        assertRejected("chk_tx_status", authorization().copy(status = "DONE"))
        assertRejected("chk_tx_settle_status", authorization().copy(settleStatus = "PARTIAL"))
        assertRejected("chk_tx_amount_positive", authorization().copy(amountValue = 0L))
        assertRejected("chk_tx_currency", authorization().copy(amountCurrency = "eur"))
        assertRejected("chk_tx_type", authorization().copy(txType = "SETTLE"))

        assertEquals(0, countRows())
    }

    @Test
    fun `should accept the tx types the domain writes`() {
        // Tx subclasses write JournalType names: AuthorizationTx, CaptureTx, RefundTx, SettleTx, PayoutTx
        val txTypes = listOf("AUTHORIZATION", "CAPTURE", "REFUND", "SETTLEMENT", "PAYOUT")

        var txId = 1L
        for (txType in txTypes) {
            paymentTxMapper.upsert(authorization().copy(txId = txId, txType = txType))
            txId++
        }

        assertEquals(5, countRows())
    }

    @Test
    fun `should reject a PSP_FEE tx because chk_tx_type does not list it`() {
        // Tx.PspFeeTx writes txType = JournalType.PSP_FEE, so a PspFeeTx cannot be stored today
        assertRejected("chk_tx_type", authorization().copy(txType = "PSP_FEE"))
    }

    @Test
    fun `second concurrent upsert of the same tx id should wait for the first and then overwrite it`() {
        paymentTxMapper.upsert(authorization())
        paymentTxMapper.upsert(capture("PENDING", "UNMATCHED", null, null))

        val executor = Executors.newSingleThreadExecutor()
        // transaction A confirms the capture and stays open: the row is locked
        val transactionA = transactionManager.getTransaction(DefaultTransactionDefinition())
        var committed = false
        try {
            paymentTxMapper.upsert(capture("SUCCESS", "UNMATCHED", null, null))

            // worker B fails the same capture on another thread, on its own connection
            val upsertByB: Future<Boolean> = executor.submit(
                Callable {
                    paymentTxMapper.upsert(capture("FAILED", "UNMATCHED", null, null))
                    true
                }
            )
            assertTrue(isStillWaiting(upsertByB), "B must wait for A's row lock")

            transactionManager.commit(transactionA)
            committed = true
            upsertByB.get(5, TimeUnit.SECONDS)
        } finally {
            if (!committed) {
                transactionManager.rollback(transactionA)
            }
            executor.shutdownNow()
        }

        // nothing told B that A had already moved the row to SUCCESS: the last writer wins
        assertEquals(
            "FAILED",
            jdbcTemplate.queryForObject(
                "SELECT status FROM payment_tx WHERE tx_id = ?",
                String::class.java,
                captureTxId
            )
        )
    }

    // --------------------------------------------------------------- findByPaymentId

    @Test
    fun `should find the txs of a payment with every field mapped`() {
        paymentTxMapper.upsert(authorization())
        paymentTxMapper.upsert(capture("SUCCESS", "MATCHED", "BATCH-42", 2955L))
        jdbcTemplate.update("UPDATE payment_tx SET created_at = '2026-07-01 12:00:00' WHERE tx_id = ?", authTxId)
        jdbcTemplate.update("UPDATE payment_tx SET created_at = '2026-07-01 12:00:05' WHERE tx_id = ?", captureTxId)

        val results = paymentTxMapper.findByPaymentId(paymentId)

        val expectedAuthorization = authorization().copy(createdAt = Instant.parse("2026-07-01T12:00:00Z"))
        val expectedCapture = capture(
            "SUCCESS",
            "MATCHED",
            "BATCH-42",
            2955L
        ).copy(createdAt = Instant.parse("2026-07-01T12:00:05Z"))
        assertEquals(listOf(expectedAuthorization, expectedCapture), results)
    }

    @Test
    fun `should find only the txs of that payment, oldest first`() {
        paymentTxMapper.upsert(authorization().copy(txId = 1L))
        paymentTxMapper.upsert(authorization().copy(txId = 2L))
        paymentTxMapper.upsert(authorization().copy(txId = 3L))
        paymentTxMapper.upsert(authorization().copy(txId = 4L, paymentId = otherPaymentId))
        // created_at order is the opposite of tx_id order
        jdbcTemplate.update("UPDATE payment_tx SET created_at = '2026-07-01 12:00:03' WHERE tx_id = 1")
        jdbcTemplate.update("UPDATE payment_tx SET created_at = '2026-07-01 12:00:02' WHERE tx_id = 2")
        jdbcTemplate.update("UPDATE payment_tx SET created_at = '2026-07-01 12:00:01' WHERE tx_id = 3")

        val results = paymentTxMapper.findByPaymentId(paymentId)

        assertEquals(3, results.size)
        assertEquals(3L, results[0].txId)
        assertEquals(2L, results[1].txId)
        assertEquals(1L, results[2].txId)
    }

    @Test
    fun `should return an empty list for a payment without txs`() {
        paymentTxMapper.upsert(authorization())

        val results = paymentTxMapper.findByPaymentId(otherPaymentId)

        assertEquals(0, results.size)
    }

    @Test
    fun `should read created_at as UTC when the JVM default timezone is not UTC`() {
        paymentTxMapper.upsert(authorization())
        jdbcTemplate.update("UPDATE payment_tx SET created_at = '2026-07-01 12:00:00' WHERE tx_id = ?", authTxId)

        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Amsterdam"))

            val results = paymentTxMapper.findByPaymentId(paymentId)

            assertEquals(Instant.parse("2026-07-01T12:00:00Z"), results[0].createdAt)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // --------------------------------------------------------------- test helpers

    private fun authorization(): PaymentTxEntity {
        return PaymentTxEntity(
            txId = authTxId,
            txType = "AUTHORIZATION",
            paymentId = paymentId,
            paymentIntentId = paymentIntentId,
            parentTxId = null,
            acquirerReference = "psp-auth-ref",
            amountValue = 3000L,
            amountCurrency = "EUR",
            status = "SUCCESS",
            settleStatus = null,
            acquirerBatchRef = null,
            settledAmountValue = null
        )
    }

    private fun capture(status: String, settleStatus: String, acquirerBatchRef: String?, settledAmountValue: Long?): PaymentTxEntity {
        return PaymentTxEntity(
            txId = captureTxId,
            txType = "CAPTURE",
            paymentId = paymentId,
            paymentIntentId = paymentIntentId,
            parentTxId = authTxId,
            acquirerReference = "psp-capture-ref",
            amountValue = 3000L,
            amountCurrency = "EUR",
            status = status,
            settleStatus = settleStatus,
            acquirerBatchRef = acquirerBatchRef,
            settledAmountValue = settledAmountValue
        )
    }

    private fun assertRejected(constraintName: String, entity: PaymentTxEntity) {
        val exception = assertThrows(DataIntegrityViolationException::class.java) {
            paymentTxMapper.upsert(entity)
        }
        assertTrue(exception.message!!.contains(constraintName), exception.message)
    }

    private fun countRows(): Int {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_tx", Int::class.java)!!
    }

    /** True when the call has not returned within 700 ms, which means it is blocked on a lock. */
    private fun isStillWaiting(future: Future<Boolean>): Boolean {
        try {
            future.get(700, TimeUnit.MILLISECONDS)
            return false
        } catch (@Suppress("SwallowedException") e: TimeoutException) { // the timeout IS the answer
            return true
        }
    }
}
