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
 * all the way to Payment.status = SETTLED, checking every milestone (M0..M15) in between.
 *
 * The entire topology (keycloak, edge-db, central-db, kafka, redis + the 4 services) runs as
 * real containers; see PlatformStack. This test only makes HTTP calls and SQL queries.
 *
 * Run: mvn -f e2e-tests/pom.xml clean verify   (Docker/OrbStack running; NOT -pl e2e-tests — it's out of
 * the reactor). The pom builds the 4 service images from the working tree first (payment-platform-e2e/<module>:local).
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
        assertThat(edgeCount("SELECT count(*) FROM outbox_event WHERE aggregate_id='$publicId'"))
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
            assertThat(edgeCount("SELECT count(*) FROM outbox_event WHERE event_type='payment_authorized' AND aggregate_id='$publicId'"))
                .isGreaterThanOrEqualTo(1L)
            assertThat(edgeScalar("SELECT status FROM payment_intents WHERE payment_intent_id=$pkInt"))
                .isEqualTo("AUTHORIZED")
        }

        // ---- M3: local outbox forwarded to central (edge-worker) --------------
        await().atMost(forwarding).pollInterval(poll).untilAsserted {
            assertThat(edgeScalar("SELECT status FROM outbox_event WHERE event_type='payment_authorized' AND aggregate_id='$publicId' LIMIT 1"))
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

        // ---- M6: central Payment aggregate born (AUTHORIZED, or already further) --
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralScalar("SELECT status FROM payments WHERE payment_intent_id=$pkInt"))
                .isIn("AUTHORIZED", "SENT_FOR_SETTLE", "CAPTURED", "SETTLED") // may race past by poll time
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
            // the card hold: 3000 on both auth accounts
            assertThat(postings(authReceivable, "AUTHORIZATION", paymentIdSub)).containsExactly("DEBIT|3000")
            assertThat(postings(authLiability, "AUTHORIZATION", paymentIdSub)).containsExactly("CREDIT|3000")
        }

        // ---- M8: capture in-flight (SENT_FOR_SETTLE) --------------------------
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralScalar("SELECT status FROM payments WHERE payment_intent_id=$pkInt"))
                .isIn("SENT_FOR_SETTLE", "CAPTURED", "SETTLED") // may race past by poll time
            assertThat(centralCount("SELECT count(*) FROM outbox_event WHERE event_type='capture_submitted' AND status='SENT' AND aggregate_id='$publicId'"))
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
            // the hold is released, the PSP now owes us 3000, and the 3000 waits in suspense
            assertThat(postings(authReceivable, "CAPTURE", paymentIdSub)).containsExactly("CREDIT|3000")
            assertThat(postings(authLiability, "CAPTURE", paymentIdSub)).containsExactly("DEBIT|3000")
            assertThat(postings(pspReceivable, "CAPTURE", paymentIdSub)).containsExactly("DEBIT|3000")
            assertThat(postings(suspense, "CAPTURE", paymentIdSub)).containsExactly("CREDIT|3000")
        }

        // ---- M10: marketplace allocation to sellers + commission --------------
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralCount("SELECT count(*) FROM journal_entries WHERE payment_id=$paymentIdSub AND journal_type='INTERNAL_TRANSFER'"))
                .isGreaterThanOrEqualTo(2L)
            assertThat(centralCount("SELECT count(*) FROM transfers WHERE payment_id=$paymentIdSub AND status='TRANSFERRED' AND target_account='$seller1'"))
                .isEqualTo(1L)
            assertThat(centralCount("SELECT count(*) FROM transfers WHERE payment_id=$paymentIdSub AND status='TRANSFERRED' AND target_account='$seller2'"))
                .isEqualTo(1L)
            // both Commission splits land on the operator's commission account (not the merchant id)
            assertThat(centralCount("SELECT count(*) FROM transfers WHERE payment_id=$paymentIdSub AND status='TRANSFERRED' AND source_account='$suspense' AND target_account='$commission'"))
                .isEqualTo(2L)
            // the platform fee is taken from that same commission account into the fee reserve
            assertThat(centralCount("SELECT count(*) FROM transfers WHERE payment_id=$paymentIdSub AND status='TRANSFERRED' AND source_account='$commission' AND target_account='$feeReserve'"))
                .isEqualTo(1L)
            assertThat(centralCount("SELECT count(*) FROM journal_entries WHERE payment_id=$paymentIdSub AND journal_type='COMMISSION_FEE'"))
                .isEqualTo(1L)
            // the 3000 leaves suspense in four splits
            assertThat(postings(suspense, "INTERNAL_TRANSFER", paymentIdSub))
                .containsExactlyInAnyOrder("DEBIT|1400", "DEBIT|1400", "DEBIT|100", "DEBIT|100")
            // each seller is owed its split
            assertThat(postings(seller1, "INTERNAL_TRANSFER", paymentIdSub)).containsExactly("CREDIT|1400")
            assertThat(postings(seller2, "INTERNAL_TRANSFER", paymentIdSub)).containsExactly("CREDIT|1400")
            // the operator is owed both commissions...
            assertThat(postings(commission, "INTERNAL_TRANSFER", paymentIdSub))
                .containsExactlyInAnyOrder("CREDIT|100", "CREDIT|100")
            // ...minus our fee, which goes into the fee reserve
            assertThat(postings(commission, "COMMISSION_FEE", paymentIdSub)).containsExactly("DEBIT|50")
            assertThat(postings(feeReserve, "COMMISSION_FEE", paymentIdSub)).containsExactly("CREDIT|50")
        }

        // ---- M11: settlement reconciled (MATCHED) + settlement journal --------
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralCount("SELECT count(*) FROM payment_tx WHERE payment_id=$paymentIdSub AND tx_type='SETTLEMENT' AND settle_status='MATCHED'"))
                .isGreaterThanOrEqualTo(1L)
            assertThat(centralCount("SELECT count(*) FROM journal_entries WHERE payment_id=$paymentIdSub AND journal_type='SETTLEMENT'"))
                .isEqualTo(1L)
            // the PSP pays: 2955 reaches our bank, 45 is its fee, and its 3000 debt is cleared
            assertThat(postings(platformCash, "SETTLEMENT", paymentIdSub)).containsExactly("DEBIT|2955")
            assertThat(postings(pspFeeExpense, "SETTLEMENT", paymentIdSub)).containsExactly("DEBIT|45")
            assertThat(postings(pspReceivable, "SETTLEMENT", paymentIdSub)).containsExactly("CREDIT|3000")
        }

        // ---- M12: terminal SETTLED --------------------------------------------
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralScalar("SELECT status FROM payments WHERE payment_intent_id=$pkInt"))
                .isEqualTo("SETTLED")
        }

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

        // M14: every posting of this payment is on an account that exists in account_directory,
        // with the same account type (postings.account_code has no foreign key, so check it here)
        assertThat(
            centralCount(
                "SELECT count(*) FROM postings p JOIN journal_entries j ON j.id = p.journal_id " +
                    "LEFT JOIN account_directory a ON a.account_code = p.account_code " +
                    "WHERE j.payment_id=$paymentIdSub AND (a.account_code IS NULL OR a.account_type <> p.account_type)"
            )
        ).withFailMessage("Found postings on accounts missing from account_directory").isEqualTo(0L)

        // Seller and operator accounts are touched only by the journals above, nothing else
        assertThat(allPostings(seller1, paymentIdSub)).containsExactly("INTERNAL_TRANSFER|CREDIT|1400")
        assertThat(allPostings(seller2, paymentIdSub)).containsExactly("INTERNAL_TRANSFER|CREDIT|1400")
        assertThat(allPostings(commission, paymentIdSub))
            .containsExactlyInAnyOrder("INTERNAL_TRANSFER|CREDIT|100", "INTERNAL_TRANSFER|CREDIT|100", "COMMISSION_FEE|DEBIT|50")
        assertThat(allPostings(directPayable, paymentIdSub)).isEmpty()

        // M15: account balances after this payment (3000 captured, 45 PSP fee, 50 platform fee).
        // Positive = the account's normal side. Clearing accounts are back to 0; what is left is
        // cash + expense on one side (2955 + 45) and what we owe on the other (1400+1400+150+50).
        assertThat(debitBalance(authReceivable, paymentIdSub)).isEqualTo(0L)
        assertThat(creditBalance(authLiability, paymentIdSub)).isEqualTo(0L)
        assertThat(debitBalance(pspReceivable, paymentIdSub)).isEqualTo(0L)
        assertThat(creditBalance(suspense, paymentIdSub)).isEqualTo(0L)
        assertThat(creditBalance(seller1, paymentIdSub)).isEqualTo(1400L)
        assertThat(creditBalance(seller2, paymentIdSub)).isEqualTo(1400L)
        assertThat(creditBalance(commission, paymentIdSub)).isEqualTo(150L)   // 100 + 100 - 50 fee
        assertThat(creditBalance(feeReserve, paymentIdSub)).isEqualTo(50L)
        assertThat(debitBalance(platformCash, paymentIdSub)).isEqualTo(2955L)
        assertThat(debitBalance(pspFeeExpense, paymentIdSub)).isEqualTo(45L)

        // These journal types belong to later batch jobs, not this flow:
        assertThat(centralCount("SELECT count(*) FROM journal_entries WHERE payment_id=$paymentIdSub AND journal_type IN ('REFUND','PAYOUT','REVENUE_RECOGNITION')"))
            .isEqualTo(0L)

        printTAccounts("Marketplace payment $publicId (3000 EUR, 2 sellers + commission)", paymentIdSub)
    }

    /**
     * One direct sale (no splits) of 5000 EUR, then an exact comparison of everything the
     * ledger holds for that payment. The simulator settles with a 1.5% PSP fee (75) and the
     * platform fee is a fixed 50, so every row is known in advance.
     */
    @Test
    fun `direct sale of 5000 records exactly the expected journals, postings, transfers and txs`() {
        val token = E2eSupport.fetchToken(PlatformStack.keycloakBaseUrl)
        val publicId = createAndAuthorize(token, directSaleBody())
        val pkInt = PublicIdFactory.toInternalId(publicId)

        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(centralScalar("SELECT status FROM payments WHERE payment_intent_id=$pkInt"))
                .isEqualTo("SETTLED")
        }
        val paymentIdSub = "(SELECT payment_id FROM payments WHERE payment_intent_id=$pkInt)"

        // the payment
        assertThat(
            centralRows(
                "SELECT processing_model || '|' || total_amount_value || '|' || captured_amount_value || '|' || status " +
                    "FROM payments WHERE payment_intent_id=$pkInt"
            )
        ).containsExactly("DIRECT_MERCHANT|5000|5000|SETTLED")

        // one journal of each type, no more. The allocation (INTERNAL_TRANSFER, COMMISSION_FEE) runs
        // asynchronously after the CAPTURE journal, so it can land after the payment is SETTLED: wait for it.
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(
                centralRows("SELECT journal_type FROM journal_entries WHERE payment_id=$paymentIdSub ORDER BY 1")
            ).containsExactlyInAnyOrder("AUTHORIZATION", "CAPTURE", "COMMISSION_FEE", "INTERNAL_TRANSFER", "SETTLEMENT")
        }

        // every posting: JOURNAL_TYPE|ACCOUNT|DIRECTION|AMOUNT
        assertThat(
            centralRows(
                "SELECT j.journal_type || '|' || p.account_code || '|' || p.direction || '|' || p.amount " +
                    "FROM postings p JOIN journal_entries j ON j.id = p.journal_id " +
                    "WHERE j.payment_id=$paymentIdSub ORDER BY 1"
            )
        ).containsExactlyInAnyOrder(
            "AUTHORIZATION|$authLiability|CREDIT|5000",
            "AUTHORIZATION|$authReceivable|DEBIT|5000",
            "CAPTURE|$authLiability|DEBIT|5000",
            "CAPTURE|$authReceivable|CREDIT|5000",
            "CAPTURE|$suspense|CREDIT|5000",
            "CAPTURE|$pspReceivable|DEBIT|5000",
            "COMMISSION_FEE|$directPayable|DEBIT|50",
            "COMMISSION_FEE|$feeReserve|CREDIT|50",
            "INTERNAL_TRANSFER|$suspense|DEBIT|5000",
            "INTERNAL_TRANSFER|$directPayable|CREDIT|5000",
            "SETTLEMENT|$platformCash|DEBIT|4925",
            "SETTLEMENT|$pspFeeExpense|DEBIT|75",
            "SETTLEMENT|$pspReceivable|CREDIT|5000"
        )

        // every transfer: TYPE|SOURCE|TARGET|AMOUNT|STATUS (also asynchronous: wait until both are TRANSFERRED)
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            assertThat(
                centralRows(
                    "SELECT transfer_type || '|' || source_account || '|' || target_account || '|' || amount_value || '|' || status " +
                        "FROM transfers WHERE payment_id=$paymentIdSub ORDER BY 1"
                )
            ).containsExactlyInAnyOrder(
                "COMMISSION_FEE|$directPayable|$feeReserve|50|TRANSFERRED",
                "INTERNAL_TRANSFER|$suspense|$directPayable|5000|TRANSFERRED"
            )
        }

        // every tx: TYPE|STATUS|SETTLE_STATUS
        assertThat(
            centralRows(
                "SELECT tx_type || '|' || status || '|' || COALESCE(settle_status, '-') " +
                    "FROM payment_tx WHERE payment_id=$paymentIdSub ORDER BY 1"
            )
        ).containsExactlyInAnyOrder(
            "AUTHORIZATION|SUCCESS|-",
            "CAPTURE|SUCCESS|MATCHED",
            "SETTLEMENT|SUCCESS|MATCHED"
        )

        // balances this payment leaves behind: 4925 + 75 on one side, 4950 + 50 on the other
        assertThat(debitBalance(authReceivable, paymentIdSub)).isEqualTo(0L)
        assertThat(creditBalance(authLiability, paymentIdSub)).isEqualTo(0L)
        assertThat(debitBalance(pspReceivable, paymentIdSub)).isEqualTo(0L)
        assertThat(creditBalance(suspense, paymentIdSub)).isEqualTo(0L)
        assertThat(creditBalance(directPayable, paymentIdSub)).isEqualTo(4950L)
        assertThat(creditBalance(feeReserve, paymentIdSub)).isEqualTo(50L)
        assertThat(debitBalance(platformCash, paymentIdSub)).isEqualTo(4925L)
        assertThat(debitBalance(pspFeeExpense, paymentIdSub)).isEqualTo(75L)

        printTAccounts("Direct sale $publicId (5000 EUR)", paymentIdSub)
    }

    /** createPayment + authorize over HTTP; returns the public payment intent id. */
    private fun createAndAuthorize(token: String, body: String): String {
        val create = E2eSupport.postJson(
            url = "${PlatformStack.paymentServiceBaseUrl}/api/v1/payments",
            token = token,
            body = body,
            extraHeaders = mapOf("Idempotency-Key" to E2eSupport.newUuidV7())
        )
        assertThat(create.status)
            .withFailMessage("createPayment failed: ${create.status} ${create.rawBody}")
            .isEqualTo(201)
        val publicId = create.body!!.get("paymentIntentId").asText()

        val authorize = E2eSupport.postJson(
            url = "${PlatformStack.paymentServiceBaseUrl}/api/v1/payments/$publicId/authorize",
            token = token,
            body = "{}"
        )
        assertThat(authorize.status)
            .withFailMessage("authorize failed: ${authorize.status} ${authorize.rawBody}")
            .isEqualTo(200)
        return publicId
    }

    // --------------------------------------------------------------- accounts (ACCOUNT_TYPE.MERCHANT.[SELLER].CURRENCY)
    private val authReceivable = "AUTH_RECEIVABLE.GLOBAL.EUR"
    private val authLiability = "AUTH_LIABILITY.GLOBAL.EUR"
    private val pspReceivable = "PSP_RECEIVABLE.GLOBAL.EUR"
    private val platformCash = "PLATFORM_CASH.GLOBAL.EUR"
    private val pspFeeExpense = "PSP_FEE_EXPENSE.GLOBAL.EUR"
    private val suspense = "CAPTURE_SUSPENSE.MARKETPLACE-5.EUR"
    private val commission = "MERCHANT_COMMISSION_PAYABLE.MARKETPLACE-5.EUR"
    private val feeReserve = "PLATFORM_FEE_RESERVE.MARKETPLACE-5.EUR"
    private val directPayable = "MERCHANT_DIRECT_PAYABLE.MARKETPLACE-5.EUR"
    private val seller1 = "SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR"
    private val seller2 = "SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-2.EUR"

    /** Postings of one journal type on one account, for this payment, as DIRECTION|AMOUNT. */
    private fun postings(accountCode: String, journalType: String, paymentIdSub: String): List<String> {
        return centralRows(
            "SELECT p.direction || '|' || p.amount FROM postings p JOIN journal_entries j ON j.id = p.journal_id " +
                "WHERE j.payment_id=$paymentIdSub AND j.journal_type='$journalType' AND p.account_code='$accountCode'"
        )
    }

    /** Every posting on one account for this payment, as JOURNAL_TYPE|DIRECTION|AMOUNT. */
    private fun allPostings(accountCode: String, paymentIdSub: String): List<String> {
        return centralRows(
            "SELECT j.journal_type || '|' || p.direction || '|' || p.amount FROM postings p JOIN journal_entries j ON j.id = p.journal_id " +
                "WHERE j.payment_id=$paymentIdSub AND p.account_code='$accountCode'"
        )
    }

    /** Debits minus credits on the account, counting only the postings of this payment's journals. */
    private fun debitBalance(accountCode: String, paymentIdSub: String): Long {
        return signedSum(accountCode, paymentIdSub, "DEBIT")
    }

    /** Credits minus debits on the account, counting only the postings of this payment's journals. */
    private fun creditBalance(accountCode: String, paymentIdSub: String): Long {
        return signedSum(accountCode, paymentIdSub, "CREDIT")
    }

    private fun signedSum(accountCode: String, paymentIdSub: String, positiveDirection: String): Long {
        val sql = "SELECT COALESCE(SUM(CASE WHEN p.direction='$positiveDirection' THEN p.amount ELSE -p.amount END), 0) " +
            "FROM postings p JOIN journal_entries j ON j.id = p.journal_id " +
            "WHERE j.payment_id=$paymentIdSub AND p.account_code='$accountCode'"
        return centralCount(sql)
    }

    // --------------------------------------------------------------- T-accounts (output only)

    /**
     * Prints every account this payment touched as a T-account: debits left, credits right, the journal
     * each line came from, and the balance. Also appended to target/t-accounts.txt.
     */
    private fun printTAccounts(title: String, paymentIdSub: String) {
        val rows = centralRows(
            "SELECT p.account_code || '|' || j.journal_type || '|' || p.direction || '|' || p.amount " +
                "FROM postings p JOIN journal_entries j ON j.id = p.journal_id " +
                "WHERE j.payment_id=$paymentIdSub ORDER BY p.account_code, p.id"
        )
        // account -> its postings, in the order they were written
        val accounts = LinkedHashMap<String, MutableList<List<String>>>()
        for (row in rows) {
            val parts = row.split("|")
            val postings = accounts.getOrPut(parts[0]) { mutableListOf() }
            postings.add(parts)
        }

        val out = StringBuilder()
        out.append("\n==== T-accounts: ").append(title).append(" ====\n")
        var allDebits = 0L
        var allCredits = 0L
        for ((account, postings) in accounts) {
            val debits = mutableListOf<String>()
            val credits = mutableListOf<String>()
            var debitTotal = 0L
            var creditTotal = 0L
            for (posting in postings) {
                val line = posting[1].padEnd(20) + posting[3].padStart(8)
                val amount = posting[3].toLong()
                if (posting[2] == "DEBIT") {
                    debits.add(line)
                    debitTotal += amount
                } else {
                    credits.add(line)
                    creditTotal += amount
                }
            }
            allDebits += debitTotal
            allCredits += creditTotal

            out.append("\n").append(account).append("\n")
            out.append("  ").append("DEBIT".padEnd(28)).append(" | ").append("CREDIT").append("\n")
            var i = 0
            while (i < debits.size || i < credits.size) {
                val left = if (i < debits.size) debits[i] else ""
                val right = if (i < credits.size) credits[i] else ""
                out.append("  ").append(left.padEnd(28)).append(" | ").append(right).append("\n")
                i++
            }
            out.append("  ").append("-".repeat(28)).append("-+-").append("-".repeat(28)).append("\n")
            out.append("  ").append(("total".padEnd(20) + debitTotal.toString().padStart(8))).append(" | ")
                .append("total".padEnd(20) + creditTotal.toString().padStart(8)).append("\n")
            val balance = if (debitTotal > creditTotal) {
                "DEBIT ${debitTotal - creditTotal}"
            } else if (creditTotal > debitTotal) {
                "CREDIT ${creditTotal - debitTotal}"
            } else {
                "0 (cleared)"
            }
            out.append("  balance: ").append(balance).append("\n")
        }
        out.append("\nall debits ").append(allDebits).append(" = all credits ").append(allCredits).append("\n")

        println(out)
        java.io.File("target/t-accounts.txt").appendText(out.toString())
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

    /** Every row of a one-column query, as text, in the order the query returns them. */
    private fun centralRows(sql: String): List<String> =
        E2eSupport.query(PlatformStack.centralJdbcUrl, PlatformStack.dbUser, PlatformStack.dbPass, sql) { rs -> rs.getString(1) }

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

    // MARKETPLACE-5 is the simulator target, so this payment also runs to SETTLED on its own
    private fun directSaleBody(): String = """
        {
          "orderId": "ORDER-E2E-DIRECT-1",
          "buyerId": "BUYER-E2E-2",
          "merchantAccount": "MARKETPLACE-5",
          "processingModel": "DIRECT_MERCHANT",
          "totalAmount": { "quantity": 5000, "currency": "EUR" }
        }
    """.trimIndent()
}
