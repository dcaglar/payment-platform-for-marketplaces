package com.dogancaglar.e2e

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.HttpURLConnection.HTTP_ACCEPTED
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.time.Duration

/**
 * End-to-end black-box test of account onboarding: POST /api/v1/accounts on the real payment-consumers
 * container, then every step until the accounts exist in central-db exactly as expected.
 *
 *   A0  POST /api/v1/accounts (ADMIN token) answers 202 ACCEPTED
 *   A1  central outbox holds account_creation_requested with the request as payload
 *   A2  the relay published it (outbox row SENT)
 *   A3  the merchant and its sellers exist, with the merchant settings as columns
 *   A4  every row created for the merchant is exactly the expected set (6 merchant ledger accounts,
 *       one SELLER_PAYABLE per seller), and the payment flow's lookup view finds them
 *
 * Same platform as PaymentFlowE2EIntegrationTest (PlatformStack is started once per JVM).
 * Run: mvn -f e2e-tests/pom.xml clean verify
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountCreationE2EIntegrationTest {

    private val relayed = Duration.ofSeconds(60)
    private val created = Duration.ofSeconds(60)
    private val poll = Duration.ofSeconds(1)

    @BeforeAll
    fun bootPlatform() {
        PlatformStack.start()
    }

    @Test
    fun `create account request ends with exactly the expected merchant, seller and ledger accounts`() {
        val merchant = "MARKETPLACE-E2E-1"
        val token = adminToken()

        // ---- A0: accepted ----------------------------------------------------
        val response = E2eSupport.postJson(
            url = "${PlatformStack.consumersBaseUrl}/api/v1/accounts",
            token = token,
            body = accountBody(merchant, listOf("SELLER-E2E-1-1", "SELLER-E2E-1-2"))
        )
        assertThat(response.status)
            .withFailMessage("create account failed: ${response.status} ${response.rawBody}")
            .isEqualTo(HTTP_ACCEPTED)
        assertThat(response.body!!.get("status").asText()).isEqualTo("ACCEPTED")
        assertThat(response.body!!.get("merchantAccountCode").asText()).isEqualTo(merchant)

        // ---- A1: the request is in the central outbox ---------------------------
        await().atMost(relayed).pollInterval(poll).untilAsserted {
            val payload = centralScalar(
                "SELECT payload FROM outbox_event WHERE event_type='account_creation_requested' AND " +
                    "aggregate_id='$merchant'"
            )
            assertThat(payload).isNotNull()
            val data = E2eSupport.mapper.readTree(payload).get("data")
            assertThat(data.get("merchantAccountCode").asText()).isEqualTo(merchant)
            val sellers = mutableListOf<String>()
            for (seller in data.get("sellerAccountCodes")) {
                sellers.add(seller.asText())
            }
            assertThat(sellers).containsExactly("SELLER-E2E-1-1", "SELLER-E2E-1-2")
        }

        // ---- A2: relayed to Kafka ---------------------------------------------
        await().atMost(relayed).pollInterval(poll).untilAsserted {
            assertThat(
                centralScalar(
                    "SELECT status FROM outbox_event WHERE event_type='account_creation_requested' AND " +
                        "aggregate_id='$merchant'"
                )
            )
                .isEqualTo("SENT")
        }

        // ---- A3: merchant and sellers exist ------------------------------------
        await().atMost(created).pollInterval(poll).untilAsserted {
            assertThat(
                centralRows(
                    "SELECT kind || '|' || status || '|' || currency || '|' || is_auto_captured || '|' || " +
                        "is_auto_settled || '|' || platform_fee_fixed || '|' || platform_fee_bps || '|' || " +
                        "(profile->>'legalName') || '|' || (profile->'address'->>'country') " +
                        "FROM accounts WHERE account_code='$merchant'"
                )
            ).containsExactly("MERCHANT|ACTIVE|EUR|true|false|30|150|Marketplace E2E One B.V.|NL")
        }
        assertThat(
            centralRows("SELECT account_code FROM accounts WHERE kind='SELLER' AND parent_code='$merchant' ORDER BY 1")
        )
            .containsExactly("SELLER-E2E-1-1", "SELLER-E2E-1-2")

        // ---- A4: exactly these ledger accounts, under the right owners ---------------------
        assertThat(
            centralRows(
                "SELECT account_code || '|' || parent_code || '|' || ledger_type || '|' || currency || '|' || status " +
                    "FROM accounts WHERE kind='LEDGER' AND (parent_code='$merchant' OR parent_code IN " +
                    "(SELECT account_code FROM accounts WHERE kind='SELLER' AND parent_code='$merchant')) ORDER BY 1"
            )
        ).containsExactly(
            "AUTH_LIABILITY.$merchant.EUR|$merchant|AUTH_LIABILITY|EUR|ACTIVE",
            "AUTH_RECEIVABLE.$merchant.EUR|$merchant|AUTH_RECEIVABLE|EUR|ACTIVE",
            "CAPTURE_SUSPENSE.$merchant.EUR|$merchant|CAPTURE_SUSPENSE|EUR|ACTIVE",
            "MERCHANT_COMMISSION_PAYABLE.$merchant.EUR|$merchant|MERCHANT_COMMISSION_PAYABLE|EUR|ACTIVE",
            "MERCHANT_DIRECT_PAYABLE.$merchant.EUR|$merchant|MERCHANT_DIRECT_PAYABLE|EUR|ACTIVE",
            "PLATFORM_FEE_RESERVE.$merchant.EUR|$merchant|PLATFORM_FEE_RESERVE|EUR|ACTIVE",
            "SELLER_PAYABLE.$merchant.SELLER-E2E-1-1.EUR|SELLER-E2E-1-1|SELLER_PAYABLE|EUR|ACTIVE",
            "SELLER_PAYABLE.$merchant.SELLER-E2E-1-2.EUR|SELLER-E2E-1-2|SELLER_PAYABLE|EUR|ACTIVE"
        )
        // nothing else was created for this merchant
        assertThat(centralCount("SELECT count(*) FROM accounts WHERE account_code LIKE '%E2E-1%'")).isEqualTo(11L)

        // the view the payment flow uses resolves merchant and seller for every one of them
        assertThat(
            centralRows(
                "SELECT account_type || '|' || master_account_code || '|' || COALESCE(sub_entity_id, '-') " +
                    "FROM ledger_account_directory WHERE master_account_code='$merchant' ORDER BY 1"
            )
        ).containsExactly(
            "AUTH_LIABILITY|$merchant|-",
            "AUTH_RECEIVABLE|$merchant|-",
            "CAPTURE_SUSPENSE|$merchant|-",
            "MERCHANT_COMMISSION_PAYABLE|$merchant|-",
            "MERCHANT_DIRECT_PAYABLE|$merchant|-",
            "PLATFORM_FEE_RESERVE|$merchant|-",
            "SELLER_PAYABLE|$merchant|SELLER-E2E-1-1",
            "SELLER_PAYABLE|$merchant|SELLER-E2E-1-2"
        )
    }

    @Test
    fun `the same merchant requested twice is created once and the repeat changes nothing`() {
        val merchant = "MARKETPLACE-E2E-2"
        val token = adminToken()
        val dlq = "account.creation.requested.DLQ"
        val dlqBefore = E2eSupport.topicRecordCount(PlatformStack.kafka.bootstrapServers, dlq)

        val first = E2eSupport.postJson(
            "${PlatformStack.consumersBaseUrl}/api/v1/accounts",
            token,
            accountBody(merchant, listOf("SELLER-E2E-2-1"))
        )
        assertThat(first.status).isEqualTo(HTTP_ACCEPTED)
        await().atMost(created).pollInterval(poll).untilAsserted {
            assertThat(centralCount("SELECT count(*) FROM accounts WHERE account_code='$merchant'")).isEqualTo(1L)
        }

        // same code again, with a different seller: accepted and relayed, then a no-op (creation is idempotent)
        val second = E2eSupport.postJson(
            "${PlatformStack.consumersBaseUrl}/api/v1/accounts",
            token,
            accountBody(merchant, listOf("SELLER-E2E-2-9"))
        )
        assertThat(second.status).isEqualTo(HTTP_ACCEPTED)
        await().atMost(relayed).pollInterval(poll).untilAsserted {
            assertThat(
                centralCount(
                    "SELECT count(*) FROM outbox_event WHERE event_type='account_creation_requested' AND " +
                        "aggregate_id='$merchant' AND status='SENT'"
                )
            )
                .isEqualTo(2L)
        }

        // for a while after it was published: nothing new written, nothing in the DLQ
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10)).pollInterval(poll).untilAsserted {
            assertThat(E2eSupport.topicRecordCount(PlatformStack.kafka.bootstrapServers, dlq)).isEqualTo(dlqBefore)
            assertThat(
                centralCount("SELECT count(*) FROM accounts WHERE account_code LIKE '%SELLER-E2E-2-9%'")
            ).isEqualTo(0L)
        }
        assertThat(
            centralCount("SELECT count(*) FROM accounts WHERE account_code LIKE '%SELLER-E2E-2-9%'")
        ).isEqualTo(0L)
        assertThat(centralRows("SELECT account_code FROM accounts WHERE kind='SELLER' AND parent_code='$merchant'"))
            .containsExactly("SELLER-E2E-2-1")
    }

    @Test
    fun `an account request without account write is refused`() {
        // a merchant may not create merchants: it has no account:write
        val paymentToken = E2eSupport.merchantToken("MARKETPLACE-5")

        val response = E2eSupport.postJson(
            "${PlatformStack.consumersBaseUrl}/api/v1/accounts",
            paymentToken,
            accountBody("MARKETPLACE-E2E-3", listOf("SELLER-E2E-3-1"))
        )

        assertThat(response.status).isEqualTo(HTTP_FORBIDDEN)
        assertThat(
            centralCount("SELECT count(*) FROM outbox_event WHERE aggregate_id='MARKETPLACE-E2E-3'")
        ).isEqualTo(0L)
    }

    private fun adminToken(): String = E2eSupport.userToken("backoffice-admin", "admin123")

    private fun accountBody(merchant: String, sellers: List<String>): String {
        val sellerJson = StringBuilder()
        for (seller in sellers) {
            if (sellerJson.isNotEmpty()) {
                sellerJson.append(", ")
            }
            sellerJson.append('"').append(seller).append('"')
        }
        return """
            {
              "merchantAccountCode": "$merchant",
              "legalName": "Marketplace E2E One B.V.",
              "address": { "line1": "Damrak 1", "city": "Amsterdam", "postalCode": "1012 LG", "country": "NL" },
              "industry": "5399",
              "currency": "EUR",
              "platformFeeFixed": 30,
              "platformFeeBps": 150,
              "isAutoCaptured": true,
              "isAutoSettled": false,
              "sellerAccountCodes": [ $sellerJson ]
            }
        """.trimIndent()
    }

    // --------------------------------------------------------------- query helpers
    private fun centralScalar(sql: String) =
        E2eSupport.scalarOrNull(PlatformStack.centralJdbcUrl, PlatformStack.dbUser, PlatformStack.dbPass, sql)

    private fun centralCount(sql: String) =
        E2eSupport.count(PlatformStack.centralJdbcUrl, PlatformStack.dbUser, PlatformStack.dbPass, sql)

    private fun centralRows(sql: String): List<String> =
        E2eSupport.query(
            PlatformStack.centralJdbcUrl,
            PlatformStack.dbUser,
            PlatformStack.dbPass,
            sql
        ) { rs -> rs.getString(1) }
}
