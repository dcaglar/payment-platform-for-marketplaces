package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.paymentservice.domain.model.account.AccountStatus
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.payment.OutboxEvent
import com.dogancaglar.paymentservice.domain.model.payment.Payment
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import com.dogancaglar.paymentservice.infra.adapter.outbound.serialization.JacksonConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
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
 * The authorization step written through the real facade, mappers and a real Postgres (Testcontainers,
 * schema from the real Liquibase changelog).
 *
 * A replayed payment_authorized (Redis key expired, Redis restarted, or a crash before markProcessed)
 * builds the step again with newly generated payment, tx and journal-entry ids. Only the intent is the
 * same, so the facade must recognise the replay by the intent and write nothing.
 *
 * Runs via `mvn verify -pl payment-consumers -am` (Failsafe, @Tag "integration").
 */
@Tag("integration")
@Testcontainers
@MybatisTest
@Import(CentralDbTransactionalFacadeAdapter::class, JacksonConfig::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CentralDbTransactionalFacadeAdapterIntegrationTest {

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
    private lateinit var facade: CentralDbTransactionalFacadeAdapter

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val paymentIntentId = 230392644730224640L
    private val publicPaymentIntentId = "pi_AzSxhlGCAAA"
    private val eur = Currency("EUR")
    private val amount = Amount.of(3000L, eur)

    @BeforeEach
    fun cleanDatabase() {
        // In production the relay's maintenance job creates the outbox partitions; here one catch-all partition
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS outbox_event_test PARTITION OF outbox_event DEFAULT")
        jdbcTemplate.execute("TRUNCATE TABLE postings, journal_entries, payment_tx, payments, outbox_event CASCADE")
    }

    @Test
    fun `first authorization step writes the Payment, AuthTx, AUTH journal and outbox events`() {
        val recorded = recordAuthorization(
            paymentId = 1001L,
            txId = 2001L,
            globalJournalEntryId = 3001L,
            firstOeid = 4001L
        )

        assertTrue(recorded)
        assertEquals(1, count("payments"))
        assertEquals(1, count("payment_tx"))
        assertEquals(1, count("journal_entries"))
        assertEquals(2, count("postings"))
        assertEquals(2, count("outbox_event"))
    }

    @Test
    fun `replayed authorization step writes nothing`() {
        recordAuthorization(paymentId = 1001L, txId = 2001L, globalJournalEntryId = 3001L, firstOeid = 4001L)

        // the replay generates new ids for everything; only the intent is the same
        val recorded = recordAuthorization(
            paymentId = 1002L,
            txId = 2002L,
            globalJournalEntryId = 3002L,
            firstOeid = 4003L
        )

        assertFalse(recorded)
        assertEquals(1, count("payments"))
        assertEquals(1001L, jdbcTemplate.queryForObject("SELECT payment_id FROM payments", Long::class.java))
        assertEquals(1, count("payment_tx"))
        assertEquals(1, count("journal_entries"))
        assertEquals(2, count("postings"))
        assertEquals(2, count("outbox_event"))
        assertEquals(
            listOf(4001L, 4002L),
            jdbcTemplate.queryForList("SELECT oeid FROM outbox_event ORDER BY oeid", Long::class.java)
        )
    }

    // Builds the authorization step the way ProcessPspResultProcessingService.processAuthorized does
    private fun recordAuthorization(paymentId: Long, txId: Long, globalJournalEntryId: Long, firstOeid: Long): Boolean {
        val payment = Payment.initializeFromAuthEvent(
            paymentId = PaymentId(paymentId),
            paymentIntentId = PaymentIntentId(paymentIntentId),
            buyerId = BuyerId("BUYER-1"),
            merchantAccount = "MARKETPLACE-5",
            processingModel = ProcessingModel.DIRECT_MERCHANT,
            totalAmount = amount,
            splits = emptyList()
        )
        val authTx = Tx.createAuthTx(
            txId = TxId(txId),
            payment = payment,
            acquirerReference = ""
        )
        val journalEntries = JournalEntry.authHold(
            globalJournalEntryId = globalJournalEntryId,
            authTx = authTx,
            journalIdentifier = paymentIntentId.toString(),
            authReceivable = account(LedgerAccountType.AUTH_RECEIVABLE),
            authLiability = account(LedgerAccountType.AUTH_LIABILITY)
        )
        val outboxEvents = listOf(
            outboxEvent(firstOeid, "capture_requested"),
            outboxEvent(firstOeid + 1, "journal_entries_recorded")
        )
        return facade.recordAuthorizationInLedger(payment, authTx, journalEntries, outboxEvents)
    }

    private fun account(type: LedgerAccountType): LedgerAccount {
        return LedgerAccount.fromProfile(
            AccountProfile(
                accountCode = "${type.name}.GLOBAL.EUR",
                type = type,
                masterAccountCode = "GLOBAL",
                subEntityId = null,
                currency = eur,
                status = AccountStatus.ACTIVE
            )
        )
    }

    private fun outboxEvent(oeid: Long, eventType: String): OutboxEvent {
        return OutboxEvent.createNew(
            oeid = oeid,
            partitionKey = "MARKETPLACE-5",
            eventType = eventType,
            aggregateId = publicPaymentIntentId,
            eventId = "$publicPaymentIntentId:$eventType:$oeid",
            parentEventId = null,
            payload = "{}"
        )
    }

    private fun count(table: String): Int {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)!!
    }
}
