package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.paymentservice.application.transaction.Transaction
import com.dogancaglar.paymentservice.application.transaction.TransactionFilter
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.domain.model.payment.CardBrand
import com.dogancaglar.paymentservice.domain.model.payment.CardSummary
import com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit
import com.dogancaglar.paymentservice.domain.model.payment.PaymentStatus
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.OrderId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.time.Instant

/**
 * Transactions written through the real adapter, mapper and Postgres (schema from the real Liquibase changelog).
 * Ledger events arrive at least once, so every write must be safe to repeat.
 */
@Tag("integration")
@Testcontainers
@MybatisTest
@Import(TransactionRepositoryAdapter::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransactionRepositoryAdapterIntegrationTest {

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
            registry.add("mybatis.type-handlers-package") { "com.dogancaglar.common.db.typehandler" }
        }
    }

    @Autowired
    private lateinit var repository: TransactionRepositoryAdapter

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val eur = Currency("EUR")
    private val paymentId = PaymentId(1001L)
    private val authorizedAt = Instant.parse("2026-10-02T10:00:00Z")
    private val capturedAt = Instant.parse("2026-10-02T10:00:05Z")
    private val settledAt = Instant.parse("2026-10-03T06:00:00Z")
    private val transaction = Transaction(
        paymentId = paymentId,
        paymentIntentId = PaymentIntentId(231538886965329920L),
        publicPaymentIntentId = "pi_AzaXVnPCAAA",
        merchantAccount = "MARKETPLACE-5",
        buyerId = BuyerId("BUYER-1450"),
        orderId = OrderId("ORDER-1450"),
        pspReference = "psp_ref_1",
        processingModel = ProcessingModel.MARKETPLACE,
        totalAmount = Amount.of(3000, eur),
        splits = listOf(
            PaymentSplit.of(LedgerAccountType.SELLER_PAYABLE, "SELLER-5-1", Amount.of(1400, eur)),
            PaymentSplit.of(LedgerAccountType.SELLER_PAYABLE, "SELLER-5-2", Amount.of(1400, eur)),
            PaymentSplit.of(LedgerAccountType.MERCHANT_COMMISSION_PAYABLE, "MARKETPLACE-5", Amount.of(200, eur))
        ),
        authorizedAt = authorizedAt
    )

    @BeforeEach
    fun cleanTables() {
        jdbcTemplate.execute("TRUNCATE TABLE transaction_splits, transactions")
    }

    @Test
    fun `an authorized, captured and settled payment is one transaction with its splits`() {
        repository.save(transaction)
        repository.markCaptured(paymentId, capturedAt)
        repository.markSettled(paymentId, settledAt)

        assertStored(captured = "2026-10-02 10:00:05", settled = "2026-10-03 06:00:00")
    }

    @Test
    fun `repeating every call changes nothing, the first times stay`() {
        repository.save(transaction)
        repository.markCaptured(paymentId, capturedAt)
        repository.markSettled(paymentId, settledAt)

        repository.save(transaction)
        repository.markCaptured(paymentId, capturedAt.plusSeconds(60))
        repository.markSettled(paymentId, settledAt.plusSeconds(60))

        assertStored(captured = "2026-10-02 10:00:05", settled = "2026-10-03 06:00:00")
    }

    @Test
    fun `settlement can be marked before capture`() {
        repository.save(transaction)
        repository.markSettled(paymentId, settledAt)
        repository.markCaptured(paymentId, capturedAt)

        assertStored(captured = "2026-10-02 10:00:05", settled = "2026-10-03 06:00:00")
    }

    @Test
    fun `marking a transaction that was never saved fails, so the event is retried`() {
        assertThatThrownBy { repository.markCaptured(
            paymentId,
            capturedAt
        ) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { repository.markSettled(
            paymentId,
            settledAt
        ) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(rows("SELECT payment_id::text FROM transactions")).isEmpty()
    }

    // --- reading ---

    @Test
    fun `a saved transaction reads back with its splits, but not for another merchant`() {
        repository.save(transaction)
        repository.markCaptured(paymentId, capturedAt)

        assertThat(repository.findById(paymentId, null)).isEqualTo(transaction.copy(capturedAt = capturedAt))
        assertThat(repository.findById(paymentId, "MARKETPLACE-5")).isEqualTo(transaction.copy(capturedAt = capturedAt))
        // another merchant asking for this payment does not find it
        assertThat(repository.findById(paymentId, "MARKETPLACE-1")).isNull()
        assertThat(repository.findById(PaymentId(9999L), null)).isNull()
    }

    @Test
    fun `pages come newest first and the filters narrow them`() {
        // 1001 settled, 1002 captured, 1003 authorized (MARKETPLACE-5); 2001 authorized (MARKETPLACE-1, no SELLER-5-1)
        repository.save(transaction)
        repository.markCaptured(paymentId, capturedAt)
        repository.markSettled(paymentId, settledAt)
        repository.save(other(1002L, "MARKETPLACE-5", "ORDER-1002", 60))
        repository.markCaptured(PaymentId(1002L), capturedAt.plusSeconds(60))
        repository.save(other(1003L, "MARKETPLACE-5", "ORDER-1003", 120))
        repository.save(other(2001L, "MARKETPLACE-1", "ORDER-2001", 180).copy(splits = emptyList()))

        assertThat(ids(TransactionFilter(), 0, 10)).containsExactly(2001L, 1003L, 1002L, 1001L)
        // second page of two: skip 2, take 2
        assertThat(ids(TransactionFilter(), 2, 2)).containsExactly(1002L, 1001L)
        assertThat(
            ids(TransactionFilter(merchantAccount = "MARKETPLACE-5"), 0, 10)
        ).containsExactly(1003L, 1002L, 1001L)
        assertThat(ids(TransactionFilter(orderId = "ORDER-1002"), 0, 10)).containsExactly(1002L)
        assertThat(ids(TransactionFilter(paymentId = PaymentId(1003L)), 0, 10)).containsExactly(1003L)
        assertThat(ids(TransactionFilter(sellerId = "SELLER-5-1"), 0, 10)).containsExactly(1003L, 1002L, 1001L)
        assertThat(ids(TransactionFilter(status = PaymentStatus.SETTLED), 0, 10)).containsExactly(1001L)
        assertThat(ids(TransactionFilter(status = PaymentStatus.CAPTURED), 0, 10)).containsExactly(1002L)
        assertThat(ids(TransactionFilter(status = PaymentStatus.AUTHORIZED), 0, 10)).containsExactly(2001L, 1003L)
        assertThat(
            ids(
                TransactionFilter(
                    authorizedFrom = authorizedAt.plusSeconds(60),
                    authorizedTo = authorizedAt.plusSeconds(180)
                ),
                0,
                10
            )
        )
            .containsExactly(1003L, 1002L)
        assertThat(repository.count(TransactionFilter(merchantAccount = "MARKETPLACE-5"))).isEqualTo(3L)
        assertThat(repository.count(TransactionFilter(status = PaymentStatus.AUTHORIZED))).isEqualTo(2L)
        // a page has no splits; the detail has them
        assertThat(repository.findPage(TransactionFilter(), 0, 10)[0].splits).isEmpty()
    }

    @Test
    fun `the card brand and last 4 are stored and read back, and the type filter separates direct sales from marketplace`() {
        val marketplacePaidByVisa = Transaction(
            paymentId = PaymentId(3001L), paymentIntentId = PaymentIntentId(3101L), publicPaymentIntentId = "pi_3001",
            merchantAccount = "MARKETPLACE-5", buyerId = BuyerId(
                "BUYER-1"
            ), orderId = OrderId("ORDER-3001"), pspReference = "psp_3001",
            processingModel = ProcessingModel.MARKETPLACE, totalAmount = Amount.of(3000, Currency("EUR")),
            splits = listOf(
                PaymentSplit.of(LedgerAccountType.SELLER_PAYABLE, "SELLER-5-1", Amount.of(3000, Currency("EUR")))
            ),
            authorizedAt = Instant.parse("2026-10-02T11:00:00Z"),
            cardSummary = CardSummary.of(CardBrand.VISA, "4242")
        )
        val directSaleWithoutCard = Transaction(
            paymentId = PaymentId(3002L), paymentIntentId = PaymentIntentId(3102L), publicPaymentIntentId = "pi_3002",
            merchantAccount = "MARKETPLACE-5", buyerId = BuyerId(
                "BUYER-2"
            ), orderId = OrderId("ORDER-3002"), pspReference = "psp_3002",
            processingModel = ProcessingModel.DIRECT_MERCHANT, totalAmount = Amount.of(5000, Currency("EUR")),
            splits = emptyList(),
            authorizedAt = Instant.parse("2026-10-02T11:01:00Z"),
            cardSummary = null
        )

        repository.save(marketplacePaidByVisa)
        repository.save(directSaleWithoutCard)

        assertThat(
            repository.findById(PaymentId(3001L), "MARKETPLACE-5")!!.cardSummary
        ).isEqualTo(CardSummary.of(CardBrand.VISA, "4242"))
        assertThat(repository.findById(PaymentId(3002L), "MARKETPLACE-5")!!.cardSummary).isNull()
        // the list carries the card too
        assertThat(
            repository.findPage(TransactionFilter(orderId = "ORDER-3001"), 0, 10)[0].cardSummary
        ).isEqualTo(CardSummary.of(CardBrand.VISA, "4242"))
        assertThat(
            repository.findPage(TransactionFilter(processingModel = ProcessingModel.MARKETPLACE), 0, 10)[0].paymentId
        ).isEqualTo(PaymentId(3001L))
        assertThat(repository.count(TransactionFilter(processingModel = ProcessingModel.MARKETPLACE))).isEqualTo(1L)
        assertThat(
            repository.findPage(
                TransactionFilter(processingModel = ProcessingModel.DIRECT_MERCHANT),
                0,
                10
            )[0].paymentId
        ).isEqualTo(PaymentId(3002L))
        assertThat(repository.count(TransactionFilter(processingModel = ProcessingModel.DIRECT_MERCHANT))).isEqualTo(1L)
    }

    private fun other(id: Long, merchant: String, orderId: String, secondsLater: Long): Transaction =
        transaction.copy(
            paymentId = PaymentId(id),
            paymentIntentId = PaymentIntentId(id + 100),
            publicPaymentIntentId = "pi_$id",
            merchantAccount = merchant,
            orderId = OrderId(orderId),
            authorizedAt = authorizedAt.plusSeconds(secondsLater)
        )

    private fun ids(filter: TransactionFilter, offset: Int, limit: Int): List<Long> {
        val ids = mutableListOf<Long>()
        for (found in repository.findPage(filter, offset, limit)) {
            ids.add(found.paymentId.value)
        }
        return ids
    }

    private fun assertStored(captured: String, settled: String) {
        assertThat(
            rows(
                "SELECT payment_id || '|' || public_payment_intent_id || '|' || merchant_account || '|' || buyer_id || '|' || " +
                    "order_id || '|' || psp_reference || '|' || processing_model || '|' || total_amount || '|' || currency || '|' || " +
                    "to_char(authorized_at, 'YYYY-MM-DD HH24:MI:SS') || '|' || to_char(captured_at, 'YYYY-MM-DD HH24:MI:SS') || '|' || " +
                    "to_char(settled_at, 'YYYY-MM-DD HH24:MI:SS') FROM transactions"
            )
        ).containsExactly(
            "1001|pi_AzaXVnPCAAA|MARKETPLACE-5|BUYER-1450|ORDER-1450|psp_ref_1|MARKETPLACE|3000|EUR|2026-10-02 10:00:00|$captured|$settled"
        )
        assertThat(
            rows(
                "SELECT line_no || '|' || account_type || '|' || account || '|' || amount FROM transaction_splits ORDER BY line_no"
            )
        )
            .containsExactly(
                "1|SELLER_PAYABLE|SELLER-5-1|1400",
                "2|SELLER_PAYABLE|SELLER-5-2|1400",
                "3|MERCHANT_COMMISSION_PAYABLE|MARKETPLACE-5|200"
            )
    }

    private fun rows(sql: String): List<String> = jdbcTemplate.queryForList(sql, String::class.java)
}
