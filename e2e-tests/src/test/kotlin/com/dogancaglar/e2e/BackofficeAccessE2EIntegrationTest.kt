package com.dogancaglar.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.util.UUID

/**
 * The back office as people use it ("inside"): each logs in through backoffice-ui and gets its bundle.
 *   seller-5-1   SELLER    only its own balance
 *   marketplace-5 MERCHANT  its own transactions, balance and sellers
 *   support-ops  SUPPORT   every merchant's transactions and sellers (merchant:all), no ledger, no onboarding
 *   finance-ops  FINANCE   what support sees (+ the ledger, step 4)
 * See new-backoffice.md, "Security".
 *
 * Data, made in @BeforeAll through the merchant API: an authorized payment of MARKETPLACE-5 and one of MARKETPLACE-1.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackofficeAccessE2EIntegrationTest {

    private var paymentOf5 = 0L
    private var paymentOf1 = 0L
    private val api get() = "${PlatformStack.consumersBaseUrl}/api/v1"

    @BeforeAll
    fun data() {
        PlatformStack.start()
        val run = UUID.randomUUID().toString().take(8)
        paymentOf5 = E2eSupport.authorizedPayment(E2eSupport.merchantToken("MARKETPLACE-5"), "MARKETPLACE-5", "SELLER-5-1", "ORDER-BO5-$run")
        paymentOf1 = E2eSupport.authorizedPayment(E2eSupport.merchantToken("MARKETPLACE-1"), "MARKETPLACE-1", "SELLER-1-1", "ORDER-BO1-$run")
    }

    // person tokens last minutes: get a fresh one per test
    private fun seller() = E2eSupport.userToken("seller-5-1", "seller123")
    private fun merchant() = E2eSupport.userToken("marketplace-5", "merchant123")
    private fun support() = E2eSupport.userToken("support-ops", "support123")
    private fun finance() = E2eSupport.userToken("finance-ops", "finance123")

    // --- seller ---

    @Test
    fun `seller sees its own balance`() {
        val result = E2eSupport.getJson("$api/balances/sellers/me", seller())

        assertThat(result.status).withFailMessage(result.rawBody).isEqualTo(HTTP_OK)
        assertThat(result.body!!.get("ownerType").asText()).isEqualTo("SELLER")
        assertThat(result.body!!.get("ownerId").asText()).isEqualTo("SELLER-5-1")
    }

    @Test
    fun `seller sees nothing else`() {
        val token = seller()

        assertThat(E2eSupport.getJson("$api/balances/sellers/SELLER-5-1", token).status).isEqualTo(HTTP_FORBIDDEN)
        assertThat(E2eSupport.getJson("$api/balances/merchants/me", token).status).isEqualTo(HTTP_FORBIDDEN)
        assertThat(E2eSupport.getJson("$api/balances/merchants/me/sellers", token).status).isEqualTo(HTTP_FORBIDDEN)
        assertThat(E2eSupport.getJson("$api/transactions/merchants/me", token).status).isEqualTo(HTTP_FORBIDDEN)
        assertThat(
            E2eSupport.getJson("$api/transactions/merchants/MARKETPLACE-5", token).status
        ).isEqualTo(HTTP_FORBIDDEN)
        assertThat(
            E2eSupport.getJson("$api/transactions/merchants/MARKETPLACE-5/$paymentOf5", token).status
        ).isEqualTo(HTTP_FORBIDDEN)
    }

    // --- merchant ---

    @Test
    fun `merchant user sees its own transactions and their details, not another merchant's`() {
        val token = merchant()

        val list = E2eSupport.getJson("$api/transactions/merchants/me?size=100", token)
        assertThat(list.status).withFailMessage(list.rawBody).isEqualTo(HTTP_OK)
        val merchants = mutableSetOf<String>()
        for (item in list.body!!.get("items")) {
            merchants.add(item.get("merchantAccount").asText())
        }
        assertThat(merchants).containsExactly("MARKETPLACE-5")
        assertThat(E2eSupport.getJson("$api/transactions/merchants/me/$paymentOf5", token).status).isEqualTo(HTTP_OK)
        assertThat(
            E2eSupport.getJson("$api/transactions/merchants/me/$paymentOf1", token).status
        ).isEqualTo(HTTP_NOT_FOUND)
        assertThat(
            E2eSupport.getJson("$api/transactions/merchants/MARKETPLACE-5", token).status
        ).isEqualTo(HTTP_FORBIDDEN) // the staff list
    }

    @Test
    fun `merchant user sees its own balance and its sellers, not another merchant's seller`() {
        val token = merchant()

        assertThat(
            E2eSupport.getJson("$api/balances/merchants/me", token).body!!.get("ownerId").asText()
        ).isEqualTo("MARKETPLACE-5")
        val sellers = E2eSupport.getJson("$api/balances/merchants/me/sellers?size=100", token)
        assertThat(sellers.status).withFailMessage(sellers.rawBody).isEqualTo(HTTP_OK)
        for (item in sellers.body!!.get("items")) {
            assertThat(item.get("ownerId").asText()).startsWith("SELLER-5-")
        }
        assertThat(E2eSupport.getJson("$api/balances/merchants/me/sellers/SELLER-5-1", token).status).isEqualTo(HTTP_OK)
        assertThat(
            E2eSupport.getJson("$api/balances/merchants/me/sellers/SELLER-1-1", token).status
        ).isEqualTo(HTTP_NOT_FOUND)
        assertThat(
            E2eSupport.getJson("$api/balances/sellers/SELLER-1-1", token).status
        ).isEqualTo(HTTP_FORBIDDEN) // the staff URL
    }

    // --- support ---

    @Test
    fun `support sees the transactions of any merchant it names, and finds a payment only under its own merchant`() {
        val token = support()

        assertThat(totalItems("$api/transactions/merchants/MARKETPLACE-5", token)).isGreaterThanOrEqualTo(1L)
        assertThat(totalItems("$api/transactions/merchants/MARKETPLACE-1", token)).isGreaterThanOrEqualTo(1L)
        assertThat(
            E2eSupport.getJson("$api/transactions/merchants/MARKETPLACE-5/$paymentOf5", token).status
        ).isEqualTo(HTTP_OK)
        assertThat(
            E2eSupport.getJson("$api/transactions/merchants/MARKETPLACE-1/$paymentOf1", token).status
        ).isEqualTo(HTTP_OK)
        assertThat(
            E2eSupport.getJson("$api/transactions/merchants/MARKETPLACE-5/$paymentOf1", token).status
        ).isEqualTo(HTTP_NOT_FOUND)
    }

    @Test
    fun `support names the merchant whose sellers it wants`() {
        val token = support()

        assertThat(
            E2eSupport.getJson("$api/balances/merchants/MARKETPLACE-1", token).body!!.get("ownerId").asText()
        ).isEqualTo("MARKETPLACE-1")
        val sellers = E2eSupport.getJson("$api/balances/merchants/MARKETPLACE-1/sellers", token)
        assertThat(sellers.status).withFailMessage(sellers.rawBody).isEqualTo(HTTP_OK)
        for (item in sellers.body!!.get("items")) {
            assertThat(item.get("ownerId").asText()).startsWith("SELLER-1-")
        }
        assertThat(E2eSupport.getJson("$api/balances/sellers/SELLER-1-1", token).status).isEqualTo(HTTP_OK)
    }

    @Test
    fun `support has no balance of its own and cannot create merchants`() {
        val token = support()

        assertThat(E2eSupport.getJson("$api/balances/merchants/me", token).status).isEqualTo(HTTP_FORBIDDEN)
        assertThat(E2eSupport.getJson("$api/balances/sellers/me", token).status).isEqualTo(HTTP_FORBIDDEN)
        assertThat(E2eSupport.postJson("$api/accounts", token, "{}").status).isEqualTo(HTTP_FORBIDDEN)
    }

    // --- finance ---

    @Test
    fun `finance sees every merchant's transactions`() {
        val token = finance()

        assertThat(
            E2eSupport.getJson("$api/transactions/merchants/MARKETPLACE-1/$paymentOf1", token).status
        ).isEqualTo(HTTP_OK)
        assertThat(totalItems("$api/transactions/merchants/MARKETPLACE-5", token)).isGreaterThanOrEqualTo(1L)
    }

    // --- nobody ---

    @Test
    fun `no token is 401`() {
        assertThat(
            E2eSupport.getJson("$api/transactions/merchants/MARKETPLACE-5", null).status
        ).isEqualTo(HTTP_UNAUTHORIZED)
        assertThat(E2eSupport.getJson("$api/balances/merchants/me", null).status).isEqualTo(HTTP_UNAUTHORIZED)
    }

    private fun totalItems(url: String, token: String): Long {
        val result = E2eSupport.getJson(url, token)
        assertThat(result.status).withFailMessage(result.rawBody).isEqualTo(HTTP_OK)
        return result.body!!.get("totalItems").asLong()
    }
}
