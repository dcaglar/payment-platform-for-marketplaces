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
import java.time.Duration
import java.util.UUID

/**
 * Finance reads a real payment's txs and journal entries through the API (/api/v1/txs/merchants/{merchantAccount}/…).
 * The amounts are the ones PaymentFlowE2EIntegrationTest verifies in the database (3000 = 1320 + 180 + 1320 + 180: 12% commission).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceTxE2EIntegrationTest {

    private val settlement = Duration.ofSeconds(240)
    private val poll = Duration.ofSeconds(1)

    private val payments get() = "${PlatformStack.paymentServiceBaseUrl}/api/v1/payments"
    private val api get() = "${PlatformStack.consumersBaseUrl}/api/v1"

    @BeforeAll
    fun bootPlatform() {
        PlatformStack.start()
    }

    @Test
    fun `finance sees a settled marketplace payment's txs, its allocation entries, and each tx's journal entries`() {
        val merchant5Backend = E2eSupport.merchantToken("MARKETPLACE-5")
        val finance = E2eSupport.userToken("finance-ops", "finance123")
        val orderId = "ORDER-TXS-${UUID.randomUUID().toString().take(8)}"
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
        val create = E2eSupport.postJson(
            payments,
            merchant5Backend,
            payment,
            mapOf("Idempotency-Key" to E2eSupport.newUuidV7())
        )
        assertThat(create.status).withFailMessage(create.rawBody).isEqualTo(HTTP_CREATED)
        val authorize = E2eSupport.postJson(
            "$payments/${create.body!!.get("paymentIntentId").asText()}/authorize",
            merchant5Backend,
            "{}"
        )
        assertThat(authorize.status).withFailMessage(authorize.rawBody).isEqualTo(HTTP_OK)

        // the payment id, once the payment is settled (from the merchant's own transaction list)
        var paymentId = ""
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            val items = E2eSupport.getJson(
                "$api/transactions/merchants/me?orderId=$orderId",
                merchant5Backend
            ).body!!.get("items")
            assertThat(items.size()).isEqualTo(1)
            assertThat(items.get(0).get("status").asText()).isEqualTo("SETTLED")
            paymentId = items.get(0).get("paymentId").asText()
        }

        // the payment: its txs and all its journal entries (the allocation runs after the capture: wait for all of them)
        val paymentUrl = "$api/txs/merchants/MARKETPLACE-5/payments/$paymentId"
        await().atMost(settlement).pollInterval(poll).untilAsserted {
            val body = E2eSupport.getJson(paymentUrl, E2eSupport.userToken("finance-ops", "finance123")).body!!
            val entryTypes = mutableListOf<String>()
            for (entry in body.get("journalEntries")) {
                entryTypes.add(entry.get("journalType").asText())
            }
            // one internal transfer per split line (1320, 180, 1320, 180), our fee, and the PSP's three
            assertThat(entryTypes).containsExactlyInAnyOrder(
                "AUTHORIZATION",
                "CAPTURE",
                "SETTLEMENT",
                "INTERNAL_TRANSFER",
                "INTERNAL_TRANSFER",
                "INTERNAL_TRANSFER",
                "INTERNAL_TRANSFER",
                "COMMISSION_FEE"
            )
        }
        val read = E2eSupport.getJson(paymentUrl, finance)
        assertThat(read.status).withFailMessage(read.rawBody).isEqualTo(HTTP_OK)
        val txTypes = mutableListOf<String>()
        var captureUrl = ""
        for (tx in read.body!!.get("txs")) {
            txTypes.add(tx.get("txType").asText())
            if (tx.get("txType").asText() == "CAPTURE") {
                captureUrl = tx.get("detailUrl").asText()
            }
        }
        assertThat(txTypes).containsExactly("AUTHORIZATION", "CAPTURE", "SETTLEMENT")
        // every posting, balanced per entry: e.g. each seller 1320, our fee 200 from the commission, the authorization hold
        val allocationLines = mutableListOf<String>()
        for (entry in read.body!!.get("journalEntries")) {
            assertThat(
                entry.get("totalDebit").get("quantity").asLong()
            ).isEqualTo(entry.get("totalCredit").get("quantity").asLong())
            for (posting in entry.get("postings")) {
                allocationLines.add(
                    entry.get("journalType").asText() + "|" + posting.get("accountType").asText() + "|" +
                        posting.get("direction").asText() + "|" + posting.get("amount").get("quantity").asLong()
                )
            }
        }
        assertThat(allocationLines).contains(
            "AUTHORIZATION|AUTH_RECEIVABLE|DEBIT|3000",
            "AUTHORIZATION|AUTH_LIABILITY|CREDIT|3000",
            "INTERNAL_TRANSFER|SELLER_PAYABLE|CREDIT|1320",
            "COMMISSION_FEE|MERCHANT_COMMISSION_PAYABLE|DEBIT|200",
            "COMMISSION_FEE|PLATFORM_FEE_RESERVE|CREDIT|200"
        )

        // one tx: the capture's journal entry, 3000 on each of its four accounts, balanced
        val capture = E2eSupport.getJson("${PlatformStack.consumersBaseUrl}$captureUrl", finance)
        assertThat(capture.status).withFailMessage(capture.rawBody).isEqualTo(HTTP_OK)
        assertThat(capture.body!!.get("journalEntries").size()).isEqualTo(1)
        val captureEntry = capture.body!!.get("journalEntries").get(0)
        assertThat(captureEntry.get("journalType").asText()).isEqualTo("CAPTURE")
        val captureLines = mutableListOf<String>()
        for (posting in captureEntry.get("postings")) {
            captureLines.add(
                posting.get("accountType").asText() + "|" + posting.get("direction").asText() + "|" + posting.get("amount").get("quantity").asLong()
            )
        }
        assertThat(captureLines).containsExactlyInAnyOrder(
            "AUTH_RECEIVABLE|CREDIT|3000",
            "AUTH_LIABILITY|DEBIT|3000",
            "PSP_RECEIVABLE|DEBIT|3000",
            "CAPTURE_SUSPENSE|CREDIT|3000"
        )
        assertThat(captureEntry.get("totalDebit").get("quantity").asLong()).isEqualTo(6000L)
        assertThat(captureEntry.get("totalCredit").get("quantity").asLong()).isEqualTo(6000L)

        // under another merchant it is not found; support (no ledger:read) may not look
        assertThat(
            E2eSupport.getJson("$api/txs/merchants/MARKETPLACE-1/payments/$paymentId", finance).status
        ).isEqualTo(HTTP_NOT_FOUND)
        assertThat(
            E2eSupport.getJson(paymentUrl, E2eSupport.userToken("support-ops", "support123")).status
        ).isEqualTo(HTTP_FORBIDDEN)
    }
}
