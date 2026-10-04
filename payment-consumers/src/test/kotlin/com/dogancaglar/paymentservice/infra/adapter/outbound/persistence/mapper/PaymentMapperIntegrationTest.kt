package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.common.db.entity.PaymentEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
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
import java.time.Instant

/**
 * One Payment per payment intent, against a real Postgres (Testcontainers) with the schema built
 * by Liquibase from the real changelog.
 *
 * A replayed payment_authorized generates a new payment_id, so only payment_intent_id can tell it
 * is the same payment: insertIfAbsent relies on the unique index on that column.
 *
 * Runs via `mvn verify -pl payment-consumers -am` (Failsafe, @Tag "integration").
 */
@Tag("integration")
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentMapperIntegrationTest {

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
    private lateinit var paymentMapper: PaymentMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val paymentIntentId = 230392644730224640L
    private val paymentId = 230392875798626304L
    private val replayPaymentId = 230392875798626999L

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE postings, journal_entries, payment_tx, payments CASCADE")
    }

    @Test
    fun `insertIfAbsent should insert the first Payment of an intent`() {
        val inserted = paymentMapper.insertIfAbsent(payment(paymentId))

        assertEquals(1, inserted)
        assertEquals(paymentId, paymentMapper.findByPaymentIntentId(paymentIntentId)!!.paymentId)
    }

    @Test
    fun `insertIfAbsent should insert nothing when the intent already has a Payment`() {
        paymentMapper.insertIfAbsent(payment(paymentId))

        // a replay: same intent, newly generated payment id
        val inserted = paymentMapper.insertIfAbsent(payment(replayPaymentId))

        assertEquals(0, inserted)
        assertEquals(1, countPayments())
        assertEquals(paymentId, paymentMapper.findByPaymentIntentId(paymentIntentId)!!.paymentId)
    }

    @Test
    fun `upsert should reject a second Payment for the same intent`() {
        paymentMapper.insertIfAbsent(payment(paymentId))

        val exception = assertThrows(DuplicateKeyException::class.java) {
            paymentMapper.upsert(payment(replayPaymentId))
        }

        assertTrue(exception.message!!.contains("idx_payments_payment_intent_id"), exception.message)
        assertEquals(1, countPayments())
    }

    @Test
    fun `upsert should still update the existing Payment by payment id`() {
        paymentMapper.insertIfAbsent(payment(paymentId))

        paymentMapper.upsert(payment(paymentId).copy(status = "CAPTURED", capturedAmountValue = 3000L))

        val row = jdbcTemplate.queryForMap(
            "SELECT status, captured_amount_value FROM payments WHERE payment_id = ?",
            paymentId
        )
        assertEquals("CAPTURED", row["status"])
        assertEquals(3000L, row["captured_amount_value"])
    }

    private fun payment(id: Long): PaymentEntity {
        val now = Instant.parse("2026-10-01T12:00:00Z")
        return PaymentEntity(
            paymentId = id,
            paymentIntentId = paymentIntentId,
            buyerId = "BUYER-1",
            merchantAccount = "MARKETPLACE-5",
            processingModel = "DIRECT_MERCHANT",
            totalAmountValue = 3000L,
            currency = "EUR",
            capturedAmountValue = 0L,
            refundedAmountValue = 0L,
            status = "AUTHORIZED",
            splitsJson = "[]",
            createdAt = now,
            updatedAt = now
        )
    }

    private fun countPayments(): Int {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Int::class.java)!!
    }
}
