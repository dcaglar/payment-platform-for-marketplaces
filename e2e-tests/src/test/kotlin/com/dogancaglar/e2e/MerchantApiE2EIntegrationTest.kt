package com.dogancaglar.e2e

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.HttpURLConnection.HTTP_CREATED
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.time.Duration
import java.util.UUID

/**
 * The API as a merchant's backend uses it ("outside"): client credentials of merchant-api-<MERCHANT>
 * (bundle MERCHANT, claim merchant_id). Every id it sends is looked up together with its merchant, so
 * another merchant's record is a 404; a merchant named in a request body must be its own (403).
 * See new-backoffice.md, "Security".
 *
 * Every test makes the payments it needs itself; nothing is shared between tests.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantApiE2EIntegrationTest {

    private val backOffice = Duration.ofSeconds(60)
    private val poll = Duration.ofMillis(500)

    private val payments get() = "${PlatformStack.paymentServiceBaseUrl}/api/v1/payments"
    private val api get() = "${PlatformStack.consumersBaseUrl}/api/v1"

    @BeforeAll
    fun bootPlatform() {
        PlatformStack.start()
    }

    // --- payments (payment-service) ---

    @Test
    fun `a merchant creates, reads and authorizes its own payment, and its transaction shows what was paid`() {
        val merchant5Backend = E2eSupport.merchantToken("MARKETPLACE-5")
        val orderId = "ORDER-OWN-${UUID.randomUUID().toString().take(8)}"
        val payment = """
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

        // create
        val create = E2eSupport.postJson(
            payments,
            merchant5Backend,
            payment,
            mapOf("Idempotency-Key" to E2eSupport.newUuidV7())
        )
        assertThat(create.status).withFailMessage(create.rawBody).isEqualTo(HTTP_CREATED)
        val paymentIntentId = create.body!!.get("paymentIntentId").asText()

        // read it back
        val read = E2eSupport.getJson("$payments/$paymentIntentId", merchant5Backend)
        assertThat(read.status).withFailMessage(read.rawBody).isEqualTo(HTTP_OK)
        assertThat(read.body!!.get("paymentIntentId").asText()).isEqualTo(paymentIntentId)
        assertThat(read.body!!.get("orderId").asText()).isEqualTo(orderId)

        // authorize
        val authorize = E2eSupport.postJson("$payments/$paymentIntentId/authorize", merchant5Backend, "{}")
        assertThat(authorize.status).withFailMessage(authorize.rawBody).isEqualTo(HTTP_OK)

        // the transaction appears in its list (asynchronously), and its detail has the splits it was paid with
        var detailUrl = ""
        await().atMost(backOffice).pollInterval(poll).untilAsserted {
            val list = E2eSupport.getJson("$api/transactions/merchants/me?orderId=$orderId", merchant5Backend)
            assertThat(list.status).withFailMessage(list.rawBody).isEqualTo(HTTP_OK)
            assertThat(list.body!!.get("items").size()).isEqualTo(1)
            detailUrl = list.body!!.get("items").get(0).get("detailUrl").asText()
        }
        val detail = E2eSupport.getJson("${PlatformStack.consumersBaseUrl}$detailUrl", merchant5Backend)
        assertThat(detail.status).withFailMessage(detail.rawBody).isEqualTo(HTTP_OK)
        assertThat(detail.body!!.get("paymentIntentId").asText()).isEqualTo(paymentIntentId)
        assertThat(detail.body!!.get("merchantAccount").asText()).isEqualTo("MARKETPLACE-5")
        assertThat(detail.body!!.get("totalAmount").get("quantity").asLong()).isEqualTo(3000L)
        assertThat(detail.body!!.get("processingModel").asText()).isEqualTo("MARKETPLACE")
        // the card the (simulated) PSP authorized: brand + last 4 only
        assertThat(detail.body!!.get("card").get("brand").asText()).isEqualTo("VISA")
        assertThat(detail.body!!.get("card").get("last4").asText()).isEqualTo("4242")
        val splits = mutableListOf<String>()
        for (split in detail.body!!.get("splits")) {
            splits.add(
                split.get("accountType").asText() + "|" + split.get("account").asText() + "|" + split.get("amount").get("quantity").asLong()
            )
        }
        assertThat(splits).containsExactlyInAnyOrder(
            "SELLER_PAYABLE|SELLER-5-1|1320",
            "MERCHANT_COMMISSION_PAYABLE|MARKETPLACE-5|180",
            "SELLER_PAYABLE|SELLER-5-2|1320",
            "MERCHANT_COMMISSION_PAYABLE|MARKETPLACE-5|180"
        )
    }

    @Test
    fun `creating a payment for another merchant is refused with 403`() {
        val merchant5Backend = E2eSupport.merchantToken("MARKETPLACE-5")
        val paymentForMarketplace1 = """
            {
              "orderId": "ORDER-FOREIGN-${UUID.randomUUID().toString().take(8)}",
              "buyerId": "BUYER-1",
              "merchantAccount": "MARKETPLACE-1",
              "processingModel": "DIRECT_MERCHANT",
              "totalAmount": { "quantity": 1000, "currency": "EUR" }
            }
        """.trimIndent()

        val result = E2eSupport.postJson(
            payments,
            merchant5Backend,
            paymentForMarketplace1,
            mapOf("Idempotency-Key" to E2eSupport.newUuidV7())
        )

        assertThat(result.status).withFailMessage(result.rawBody).isEqualTo(HTTP_FORBIDDEN)
    }

    @Test
    fun `another merchant's payment can be neither read nor authorized, and stays CREATED`() {
        val merchant1Backend = E2eSupport.merchantToken("MARKETPLACE-1")
        val merchant5Backend = E2eSupport.merchantToken("MARKETPLACE-5")
        val paymentOfMarketplace1 = """
            {
              "orderId": "ORDER-OTHER-${UUID.randomUUID().toString().take(8)}",
              "buyerId": "BUYER-1",
              "merchantAccount": "MARKETPLACE-1",
              "processingModel": "DIRECT_MERCHANT",
              "totalAmount": { "quantity": 1000, "currency": "EUR" }
            }
        """.trimIndent()
        val create = E2eSupport.postJson(
            payments,
            merchant1Backend,
            paymentOfMarketplace1,
            mapOf("Idempotency-Key" to E2eSupport.newUuidV7())
        )
        assertThat(create.status).withFailMessage(create.rawBody).isEqualTo(HTTP_CREATED)
        val paymentIntentId = create.body!!.get("paymentIntentId").asText()

        assertThat(E2eSupport.getJson("$payments/$paymentIntentId", merchant5Backend).status).isEqualTo(HTTP_NOT_FOUND)
        assertThat(
            E2eSupport.postJson("$payments/$paymentIntentId/authorize", merchant5Backend, "{}").status
        ).isEqualTo(HTTP_NOT_FOUND)

        // untouched: its own merchant still sees it as created
        val asOwner = E2eSupport.getJson("$payments/$paymentIntentId", merchant1Backend)
        assertThat(asOwner.status).withFailMessage(asOwner.rawBody).isEqualTo(HTTP_OK)
        assertThat(asOwner.body!!.get("status").asText()).isEqualTo("CREATED")
    }

    @Test
    fun `the payment API without a token is 401`() {
        assertThat(E2eSupport.getJson("$payments/pi_anything", null).status).isEqualTo(HTTP_UNAUTHORIZED)
    }

    // --- balances (payment-consumers); seed data only, the amounts are in BalanceAmountsE2EIntegrationTest ---

    @Test
    fun `its own balance`() {
        val merchant5Backend = E2eSupport.merchantToken("MARKETPLACE-5")

        val result = E2eSupport.getJson("$api/balances/merchants/me", merchant5Backend)

        assertThat(result.status).withFailMessage(result.rawBody).isEqualTo(HTTP_OK)
        assertThat(result.body!!.get("ownerType").asText()).isEqualTo("MERCHANT")
        assertThat(result.body!!.get("ownerId").asText()).isEqualTo("MARKETPLACE-5")
    }

    @Test
    fun `its sellers' balances, paged, each with a link to the seller`() {
        val merchant5Backend = E2eSupport.merchantToken("MARKETPLACE-5")

        val result = E2eSupport.getJson("$api/balances/merchants/me/sellers?page=0&size=5", merchant5Backend)

        // MARKETPLACE-5 has 10 sellers in the seed (merchants.json): a first page of 5 and a next one
        assertThat(result.status).withFailMessage(result.rawBody).isEqualTo(HTTP_OK)
        val items = result.body!!.get("items")
        assertThat(items.size()).isEqualTo(5)
        for (item in items) {
            assertThat(item.get("ownerType").asText()).isEqualTo("SELLER")
            assertThat(item.get("ownerId").asText()).startsWith("SELLER-5-")
            assertThat(
                item.get("detailUrl").asText()
            ).isEqualTo("/api/v1/balances/merchants/me/sellers/${item.get("ownerId").asText()}")
        }
        assertThat(result.body!!.get("totalItems").asLong()).isEqualTo(10L)
        assertThat(result.body!!.get("hasNext").asBoolean()).isTrue()
    }

    @Test
    fun `one of its sellers' balance, but not another merchant's seller`() {
        val merchant5Backend = E2eSupport.merchantToken("MARKETPLACE-5")

        val ownSeller = E2eSupport.getJson("$api/balances/merchants/me/sellers/SELLER-5-1", merchant5Backend)
        assertThat(ownSeller.status).withFailMessage(ownSeller.rawBody).isEqualTo(HTTP_OK)
        assertThat(ownSeller.body!!.get("ownerId").asText()).isEqualTo("SELLER-5-1")

        assertThat(
            E2eSupport.getJson("$api/balances/merchants/me/sellers/SELLER-1-1", merchant5Backend).status
        ).isEqualTo(HTTP_NOT_FOUND)
    }

    // --- transactions (payment-consumers) ---

    @Test
    fun `it sees only its own transactions, even when it asks for another merchant's`() {
        val merchant5Backend = E2eSupport.merchantToken("MARKETPLACE-5")
        val merchant1Backend = E2eSupport.merchantToken("MARKETPLACE-1")
        val run = UUID.randomUUID().toString().take(8)
        val ownOrderId = "ORDER-MINE-$run"
        val otherOrderId = "ORDER-THEIRS-$run"
        val ownPayment = """
            {
              "orderId": "$ownOrderId",
              "buyerId": "BUYER-$ownOrderId",
              "merchantAccount": "MARKETPLACE-5",
              "processingModel": "DIRECT_MERCHANT",
              "totalAmount": { "quantity": 1000, "currency": "EUR" }
            }
        """.trimIndent()
        val paymentOfMarketplace1 = """
            {
              "orderId": "$otherOrderId",
              "buyerId": "BUYER-$otherOrderId",
              "merchantAccount": "MARKETPLACE-1",
              "processingModel": "DIRECT_MERCHANT",
              "totalAmount": { "quantity": 1000, "currency": "EUR" }
            }
        """.trimIndent()

        // MARKETPLACE-5 and MARKETPLACE-1 each pay once
        val ownCreate = E2eSupport.postJson(
            payments,
            merchant5Backend,
            ownPayment,
            mapOf("Idempotency-Key" to E2eSupport.newUuidV7())
        )
        assertThat(ownCreate.status).withFailMessage(ownCreate.rawBody).isEqualTo(HTTP_CREATED)
        val ownAuthorize = E2eSupport.postJson(
            "$payments/${ownCreate.body!!.get("paymentIntentId").asText()}/authorize",
            merchant5Backend,
            "{}"
        )
        assertThat(ownAuthorize.status).withFailMessage(ownAuthorize.rawBody).isEqualTo(HTTP_OK)
        val otherCreate = E2eSupport.postJson(
            payments,
            merchant1Backend,
            paymentOfMarketplace1,
            mapOf("Idempotency-Key" to E2eSupport.newUuidV7())
        )
        assertThat(otherCreate.status).withFailMessage(otherCreate.rawBody).isEqualTo(HTTP_CREATED)
        val otherAuthorize = E2eSupport.postJson(
            "$payments/${otherCreate.body!!.get("paymentIntentId").asText()}/authorize",
            merchant1Backend,
            "{}"
        )
        assertThat(otherAuthorize.status).withFailMessage(otherAuthorize.rawBody).isEqualTo(HTTP_OK)

        // both reach the back office (each merchant sees its own)
        var otherDetailUrl = ""
        await().atMost(backOffice).pollInterval(poll).untilAsserted {
            assertThat(
                E2eSupport.getJson(
                    "$api/transactions/merchants/me?orderId=$ownOrderId",
                    merchant5Backend
                ).body!!.get("items").size()
            ).isEqualTo(1)
            val theirs = E2eSupport.getJson(
                "$api/transactions/merchants/me?orderId=$otherOrderId",
                merchant1Backend
            ).body!!.get("items")
            assertThat(theirs.size()).isEqualTo(1)
            otherDetailUrl = theirs.get(0).get("detailUrl").asText()
        }

        // its list: only MARKETPLACE-5's, with its own payment and without the other one
        val list = E2eSupport.getJson("$api/transactions/merchants/me?size=100", merchant5Backend)
        assertThat(list.status).withFailMessage(list.rawBody).isEqualTo(HTTP_OK)
        val orderIds = mutableListOf<String>()
        for (item in list.body!!.get("items")) {
            assertThat(item.get("merchantAccount").asText()).isEqualTo("MARKETPLACE-5")
            orderIds.add(item.get("orderId").asText())
        }
        assertThat(orderIds).contains(ownOrderId).doesNotContain(otherOrderId)

        // asking for MARKETPLACE-1 by filter or by order id still gives only its own (nothing of theirs)
        for (item in E2eSupport.getJson(
            "$api/transactions/merchants/me?merchantAccount=MARKETPLACE-1&size=100",
            merchant5Backend
        ).body!!.get("items")) {
            assertThat(item.get("merchantAccount").asText()).isEqualTo("MARKETPLACE-5")
        }
        assertThat(
            E2eSupport.getJson(
                "$api/transactions/merchants/me?orderId=$otherOrderId",
                merchant5Backend
            ).body!!.get("items").size()
        ).isEqualTo(0)

        // and the other merchant's transaction detail is 404
        assertThat(
            E2eSupport.getJson("${PlatformStack.consumersBaseUrl}$otherDetailUrl", merchant5Backend).status
        ).isEqualTo(HTTP_NOT_FOUND)
    }

    // --- not for merchants ---

    @Test
    fun `creating merchants is refused with 403`() {
        val merchant5Backend = E2eSupport.merchantToken("MARKETPLACE-5")

        val result = E2eSupport.postJson("$api/accounts", merchant5Backend, "{}")

        assertThat(result.status).isEqualTo(HTTP_FORBIDDEN)
    }
}
