package com.dogancaglar.e2e

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.HttpURLConnection.HTTP_CREATED
import java.net.HttpURLConnection.HTTP_OK
import java.time.Duration
import java.util.UUID

/**
 * The exact amounts the balance API shows after one payment, read as MARKETPLACE-5's backend.
 * Each test starts from a clean platform (E2eSupport.resetState: no payments, all balances 0), so the amounts
 * are absolute. MARKETPLACE-5 is auto-captured and auto-settled, so its payments run to SETTLED on their own.
 *
 * Every amount is in cents; + means we owe it to the owner. The platform fee is MARKETPLACE-5's 50 + 5% of the payment
 * (merchants.json), taken from the merchant's payable.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BalanceAmountsE2EIntegrationTest {

    private val settlement = Duration.ofSeconds(240)
    private val poll = Duration.ofSeconds(1)

    private val payments get() = "${PlatformStack.paymentServiceBaseUrl}/api/v1/payments"
    private val api get() = "${PlatformStack.consumersBaseUrl}/api/v1"

    @BeforeAll
    fun bootPlatform() {
        PlatformStack.start()
    }

    @Test
    fun `a marketplace payment of 3000 gives each seller its part and the merchant its commission minus the platform fee`() {
        E2eSupport.resetState()
        val merchant5Backend = E2eSupport.merchantToken("MARKETPLACE-5")
        val orderId = "ORDER-BALANCE-MP-${UUID.randomUUID().toString().take(8)}"
        val marketplacePayment = """
            {
              "orderId": "$orderId",
              "buyerId": "BUYER-$orderId",
              "merchantAccount": "MARKETPLACE-5",
              "processingModel": "MARKETPLACE",
              "totalAmount": { "quantity": 3000, "currency": "EUR" },
              "splits": [
                { "type": "BalanceAccount", "account": "SELLER-5-1", "amount": { "quantity": 1320, "currency": "EUR" }},
                { "type": "Commission", "amount": { "quantity": 180, "currency": "EUR" }},
                { "type": "BalanceAccount", "account": "SELLER-5-2", "amount": { "quantity": 1320, "currency": "EUR" }},
                { "type": "Commission", "amount": { "quantity": 180, "currency": "EUR" }}
              ]
            }
        """.trimIndent()

        // clean start: nothing owed to anyone
        assertThat(
            E2eSupport.getJson(
                "$api/balances/merchants/me/sellers/SELLER-5-1",
                merchant5Backend
            ).body!!.get("total").asLong()
        ).isEqualTo(0L)
        assertThat(
            E2eSupport.getJson(
                "$api/balances/merchants/me/sellers/SELLER-5-2",
                merchant5Backend
            ).body!!.get("total").asLong()
        ).isEqualTo(0L)
        assertThat(
            E2eSupport.getJson("$api/balances/merchants/me", merchant5Backend).body!!.get("total").asLong()
        ).isEqualTo(0L)

        val create = E2eSupport.postJson(
            payments,
            merchant5Backend,
            marketplacePayment,
            mapOf("Idempotency-Key" to E2eSupport.newUuidV7())
        )
        assertThat(create.status).withFailMessage(create.rawBody).isEqualTo(HTTP_CREATED)
        val paymentIntentId = create.body!!.get("paymentIntentId").asText()
        val authorize = E2eSupport.postJson("$payments/$paymentIntentId/authorize", merchant5Backend, "{}")
        assertThat(authorize.status).withFailMessage(authorize.rawBody).isEqualTo(HTTP_OK)

        // balances follow the ledger asynchronously: wait until they arrive
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            val seller1 = E2eSupport.getJson("$api/balances/merchants/me/sellers/SELLER-5-1", merchant5Backend)
            assertThat(seller1.status).withFailMessage(seller1.rawBody).isEqualTo(HTTP_OK)
            assertThat(seller1.body!!.get("total").asLong()).isEqualTo(1320L)
            assertThat(seller1.body!!.get("accounts").get(0).get("accountType").asText()).isEqualTo("SELLER_PAYABLE")
            assertThat(seller1.body!!.get("accounts").get(0).get("balance").asLong()).isEqualTo(1320L)

            val seller2 = E2eSupport.getJson("$api/balances/merchants/me/sellers/SELLER-5-2", merchant5Backend)
            assertThat(seller2.body!!.get("total").asLong()).isEqualTo(1320L)

            // merchant: commission 180 + 180, minus the platform fee (50 + 5% of 3000 = 200); nothing sold directly
            val merchant = E2eSupport.getJson("$api/balances/merchants/me", merchant5Backend)
            assertThat(merchant.status).withFailMessage(merchant.rawBody).isEqualTo(HTTP_OK)
            val merchantAccounts = HashMap<String, Long>()
            for (account in merchant.body!!.get("accounts")) {
                merchantAccounts[account.get("accountType").asText()] = account.get("balance").asLong()
            }
            assertThat(merchantAccounts).containsExactlyInAnyOrderEntriesOf(
                mapOf("MERCHANT_COMMISSION_PAYABLE" to 160L, "MERCHANT_DIRECT_PAYABLE" to 0L)
            )
            assertThat(merchant.body!!.get("total").asLong()).isEqualTo(160L)
        }

        // a seller not in this payment still has nothing
        assertThat(
            E2eSupport.getJson(
                "$api/balances/merchants/me/sellers/SELLER-5-3",
                merchant5Backend
            ).body!!.get("total").asLong()
        ).isEqualTo(0L)
    }

    @Test
    fun `a direct sale of 5000 gives the merchant 5000 minus the platform fee and the sellers nothing`() {
        E2eSupport.resetState()
        val merchant5Backend = E2eSupport.merchantToken("MARKETPLACE-5")
        val orderId = "ORDER-BALANCE-DIRECT-${UUID.randomUUID().toString().take(8)}"
        val directSale = """
            {
              "orderId": "$orderId",
              "buyerId": "BUYER-$orderId",
              "merchantAccount": "MARKETPLACE-5",
              "processingModel": "DIRECT_MERCHANT",
              "totalAmount": { "quantity": 5000, "currency": "EUR" }
            }
        """.trimIndent()

        // clean start: nothing owed to anyone
        assertThat(
            E2eSupport.getJson("$api/balances/merchants/me", merchant5Backend).body!!.get("total").asLong()
        ).isEqualTo(0L)

        val create = E2eSupport.postJson(
            payments,
            merchant5Backend,
            directSale,
            mapOf("Idempotency-Key" to E2eSupport.newUuidV7())
        )
        assertThat(create.status).withFailMessage(create.rawBody).isEqualTo(HTTP_CREATED)
        val paymentIntentId = create.body!!.get("paymentIntentId").asText()
        val authorize = E2eSupport.postJson("$payments/$paymentIntentId/authorize", merchant5Backend, "{}")
        assertThat(authorize.status).withFailMessage(authorize.rawBody).isEqualTo(HTTP_OK)

        // merchant: 5000 sold directly, minus the platform fee (50 + 5% of 5000 = 300); no commission
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            val merchant = E2eSupport.getJson("$api/balances/merchants/me", merchant5Backend)
            assertThat(merchant.status).withFailMessage(merchant.rawBody).isEqualTo(HTTP_OK)
            val merchantAccounts = HashMap<String, Long>()
            for (account in merchant.body!!.get("accounts")) {
                merchantAccounts[account.get("accountType").asText()] = account.get("balance").asLong()
            }
            assertThat(merchantAccounts).containsExactlyInAnyOrderEntriesOf(
                mapOf("MERCHANT_DIRECT_PAYABLE" to 4700L, "MERCHANT_COMMISSION_PAYABLE" to 0L)
            )
            assertThat(merchant.body!!.get("total").asLong()).isEqualTo(4700L)
        }

        assertThat(
            E2eSupport.getJson(
                "$api/balances/merchants/me/sellers/SELLER-5-1",
                merchant5Backend
            ).body!!.get("total").asLong()
        ).isEqualTo(0L)
        assertThat(
            E2eSupport.getJson(
                "$api/balances/merchants/me/sellers/SELLER-5-2",
                merchant5Backend
            ).body!!.get("total").asLong()
        ).isEqualTo(0L)
    }
}
