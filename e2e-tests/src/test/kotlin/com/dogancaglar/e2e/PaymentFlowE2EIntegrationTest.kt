package com.dogancaglar.e2e

import com.dogancaglar.common.id.PublicIdFactory
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/**
 * End-to-end black-box test of the full payment platform.
 *
 * Drives createPayment -> authorize over HTTP against the real payment-service container,
 * then asserts the two-stage transactional outbox + double-entry ledger carry the payment
 * all the way to Payment.status = SETTLED, checking every milestone (M0..M13) in between.
 *
 * The entire topology (keycloak, edge-db, central-db, kafka, redis + the 4 services) runs as
 * real containers; see PlatformStack. This test only makes HTTP calls and SQL queries.
 *
 * Run: mvn -pl e2e-tests -am verify   (Docker/OrbStack must be running; first run builds images)
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentFlowE2EIntegrationTest {

    private val forwarding = Duration.ofSeconds(90)   // edge-worker dispatch has initialDelay=30s
    private val settlement = Duration.ofSeconds(240)  // full AUTHORIZED->SETTLED chain
    private val poll = Duration.ofSeconds(1)

    @BeforeAll
    fun bootPlatform() {
        PlatformStack.start()
    }

    @Test
    fun `create then authorize drives payment to SETTLED with full ledger`() {
        val token = E2eSupport.fetchToken(PlatformStack.keycloakBaseUrl)

        // ---- 1. createPayment -------------------------------------------------
        val createBody = marketplace5Body()
        val create = E2eSupport.postJson(
            url = "${PlatformStack.paymentServiceBaseUrl}/api/v1/payments",
            token = token,
            body = createBody,
            extraHeaders = mapOf("Idempotency-Key" to E2eSupport.newUuidV7())
        )
        assertThat(create.status)
            .withFailMessage("createPayment failed: ${create.status} ${create.rawBody}")
            .isEqualTo(201)
        val publicId = create.body!!.get("paymentIntentId").asText()
        assertThat(publicId).startsWith("pi_")
        val pkInt = PublicIdFactory.toInternalId(publicId)

        // ---- M0: intent CREATED, no outbox event yet --------------------------
        assertThat(edgeScalar("SELECT status FROM payment_intents WHERE payment_intent_id=$pkInt"))
            .isEqualTo("CREATED")
        assertThat(edgeCount("SELECT count(*) FROM outbox_event"))
            .withFailMessage("createPayment must NOT write an outbox event")
            .isEqualTo(0L)

        // ---- 2. authorize -----------------------------------------------------
        val authorize = E2eSupport.postJson(
            url = "${PlatformStack.paymentServiceBaseUrl}/api/v1/payments/$publicId/authorize",
            token = token,
            body = "{}"
        )
        assertThat(authorize.status)
            .withFailMessage("authorize failed: ${authorize.status} ${authorize.rawBody}")
            .isEqualTo(200)

        // ---- M1/M2: edge outbox row created, intent AUTHORIZED -----------------
        await().atMost(forwarding).pollInterval(poll).untilAsserted {
            assertThat(edgeCount("SELECT count(*) FROM outbox_event WHERE event_type='payment_authorized'"))
                .isGreaterThanOrEqualTo(1L)
            assertThat(edgeScalar("SELECT status FROM payment_intents WHERE payment_intent_id=$pkInt"))
                .isEqualTo("AUTHORIZED")
        }

        // ---- M3: local outbox forwarded to central (edge-worker) --------------
        await().atMost(forwarding).pollInterval(poll).untilAsserted {
            assertThat(edgeScalar("SELECT status FROM outbox_event WHERE event_type='payment_authorized' LIMIT 1"))
                .isEqualTo("SENT")
        }

        // ---- M4: central outbox has the event with the right payload ----------
        await().atMost(forwarding).pollInterval(poll).untilAsserted {
            val payload = centralScalar(
                "SELECT payload FROM outbox_event " +
                    "WHERE event_type='payment_authorized' AND aggregate_id='$publicId' LIMIT 1"
            )
            assertThat(payload).isNotNull()
            val envelope = E2eSupport.mapper.readTree(payload)
            assertThat(envelope.get("eventType").asText()).isEqualTo("payment_authorized")
            assertThat(envelope.get("aggregateId").asText()).isEqualTo(publicId)
            assertThat(envelope.get("data").get("totalAmountValue").asLong()).isEqualTo(3000L)
        }

        // ---- M5: central outbox relayed to Kafka ------------------------------
        await().atMost(forwarding).pollInterval(poll).untilAsserted {
            assertThat(
                centralScalar(
                    "SELECT status FROM outbox_event " +
                        "WHERE event_type='payment_authorized' AND aggregate_id='$publicId' LIMIT 1"
                )
            ).isEqualTo("SENT")
        }

        // ---- M6: central Payment aggregate born in AUTHORIZED -----------------
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralScalar("SELECT status FROM payments WHERE payment_intent_id=$pkInt"))
                .isEqualTo("AUTHORIZED")
            assertThat(centralScalar("SELECT total_amount_value FROM payments WHERE payment_intent_id=$pkInt"))
                .isEqualTo("3000")
        }
        val paymentIdSub = "(SELECT payment_id FROM payments WHERE payment_intent_id=$pkInt)"

        // ---- M7: auth transaction + auth-hold journal -------------------------
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralScalar("SELECT status FROM payment_tx WHERE payment_id=$paymentIdSub AND tx_type='AUTHORIZATION'"))
                .isEqualTo("SUCCESS")
            assertThat(centralCount("SELECT count(*) FROM journal_entries WHERE payment_id=$paymentIdSub AND journal_type='AUTHORIZATION'"))
                .isEqualTo(1L)
        }

        // ---- M8: capture in-flight (SENT_FOR_SETTLE) --------------------------
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralScalar("SELECT status FROM payments WHERE payment_intent_id=$pkInt"))
                .isIn("SENT_FOR_SETTLE", "CAPTURED", "SETTLED") // may race past by poll time
            assertThat(centralCount("SELECT count(*) FROM outbox_event WHERE event_type='capture_submitted' AND status='SENT'"))
                .isGreaterThanOrEqualTo(1L)
        }

        // ---- M9: capture confirmed (CAPTURED) + capture journal ---------------
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralScalar("SELECT status FROM payments WHERE payment_intent_id=$pkInt"))
                .isIn("CAPTURED", "SETTLED")
            assertThat(centralScalar("SELECT captured_amount_value FROM payments WHERE payment_intent_id=$pkInt"))
                .isEqualTo("3000")
            assertThat(centralCount("SELECT count(*) FROM payment_tx WHERE payment_id=$paymentIdSub AND tx_type='CAPTURE' AND status='SUCCESS'"))
                .isEqualTo(1L)
            assertThat(centralCount("SELECT count(*) FROM journal_entries WHERE payment_id=$paymentIdSub AND journal_type='CAPTURE'"))
                .isEqualTo(1L)
        }

        // ---- M10: marketplace allocation to sellers + commission --------------
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralCount("SELECT count(*) FROM journal_entries WHERE payment_id=$paymentIdSub AND journal_type='INTERNAL_TRANSFER'"))
                .isGreaterThanOrEqualTo(2L)
            assertThat(centralCount("SELECT count(*) FROM transfers WHERE payment_id=$paymentIdSub AND status='TRANSFERRED' AND target_account LIKE 'SELLER-5-1%'"))
                .isGreaterThanOrEqualTo(1L)
            assertThat(centralCount("SELECT count(*) FROM transfers WHERE payment_id=$paymentIdSub AND status='TRANSFERRED' AND target_account LIKE 'SELLER-5-2%'"))
                .isGreaterThanOrEqualTo(1L)
            assertThat(centralCount("SELECT count(*) FROM journal_entries WHERE payment_id=$paymentIdSub AND journal_type='COMMISSION_FEE'"))
                .isGreaterThanOrEqualTo(1L)
        }

        // ---- M11: settlement reconciled (MATCHED) + settlement journal --------
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralCount("SELECT count(*) FROM payment_tx WHERE payment_id=$paymentIdSub AND tx_type='SETTLEMENT' AND settle_status='MATCHED'"))
                .isGreaterThanOrEqualTo(1L)
            assertThat(centralCount("SELECT count(*) FROM journal_entries WHERE payment_id=$paymentIdSub AND journal_type='SETTLEMENT'"))
                .isEqualTo(1L)
        }

        // ---- M12: terminal SETTLED --------------------------------------------
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralScalar("SELECT status FROM payments WHERE payment_intent_id=$pkInt"))
                .isEqualTo("SETTLED")
        }

        // ---- final ledger assertions ------------------------------------------
        // Settlement postings: DR PLATFORM_CASH 2955, DR PSP_FEE_EXPENSE 45, CR PSP_RECEIVABLES 3000
        assertThat(centralCount("SELECT count(*) FROM postings WHERE journal_id LIKE 'SETTLE:%' AND account_code LIKE 'PLATFORM_CASH%' AND direction='DEBIT' AND amount=2955"))
            .isGreaterThanOrEqualTo(1L)
        assertThat(centralCount("SELECT count(*) FROM postings WHERE journal_id LIKE 'SETTLE:%' AND account_code LIKE 'PSP_FEE_EXPENSE%' AND direction='DEBIT' AND amount=45"))
            .isGreaterThanOrEqualTo(1L)
        assertThat(centralCount("SELECT count(*) FROM postings WHERE journal_id LIKE 'SETTLE:%' AND account_code LIKE 'PSP_RECEIVABLES%' AND direction='CREDIT' AND amount=3000"))
            .isGreaterThanOrEqualTo(1L)

        // M13: every journal entry is balanced (Σ DEBIT == Σ CREDIT per journal_id)
        assertThat(
            centralCount(
                "SELECT count(*) FROM (" +
                    "  SELECT journal_id FROM postings GROUP BY journal_id " +
                    "  HAVING SUM(CASE WHEN direction='DEBIT' THEN amount ELSE 0 END) <> " +
                    "         SUM(CASE WHEN direction='CREDIT' THEN amount ELSE 0 END)" +
                    ") unbalanced"
            )
        ).withFailMessage("Found unbalanced journal entries").isEqualTo(0L)

        // These journal types belong to later batch jobs, not this flow:
        assertThat(centralCount("SELECT count(*) FROM journal_entries WHERE payment_id=$paymentIdSub AND journal_type IN ('REFUND','PAYOUT','REVENUE_RECOGNITION')"))
            .isEqualTo(0L)
    }

    // --------------------------------------------------------------- query helpers
    private fun edgeScalar(sql: String) =
        E2eSupport.scalarOrNull(PlatformStack.edgeJdbcUrl, PlatformStack.dbUser, PlatformStack.dbPass, sql)

    private fun centralScalar(sql: String) =
        E2eSupport.scalarOrNull(PlatformStack.centralJdbcUrl, PlatformStack.dbUser, PlatformStack.dbPass, sql)

    private fun edgeCount(sql: String) =
        E2eSupport.count(PlatformStack.edgeJdbcUrl, PlatformStack.dbUser, PlatformStack.dbPass, sql)

    private fun centralCount(sql: String) =
        E2eSupport.count(PlatformStack.centralJdbcUrl, PlatformStack.dbUser, PlatformStack.dbPass, sql)

    private fun marketplace5Body(): String = """
        {
          "orderId": "ORDER-E2E-1",
          "buyerId": "BUYER-E2E-1",
          "merchantAccount": "MARKETPLACE-5",
          "processingModel": "MARKETPLACE",
          "totalAmount": { "quantity": 3000, "currency": "EUR" },
          "splits": [
            { "type": "BalanceAccount", "account": "SELLER-5-1", "amount": { "quantity": 1400, "currency": "EUR" }},
            { "type": "Commission", "amount": { "quantity": 100, "currency": "EUR" }},
            { "type": "BalanceAccount", "account": "SELLER-5-2", "amount": { "quantity": 1400, "currency": "EUR" }},
            { "type": "Commission", "amount": { "quantity": 100, "currency": "EUR" }}
          ]
        }
    """.trimIndent()
}
