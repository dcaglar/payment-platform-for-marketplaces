package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.application.transaction.Transaction
import com.dogancaglar.paymentservice.application.transaction.TransactionFilter
import com.dogancaglar.paymentservice.config.SecurityConfig
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.domain.model.payment.CardBrand
import com.dogancaglar.paymentservice.domain.model.payment.CardSummary
import com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.OrderId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.ports.inbound.usecases.TransactionUseCase
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant

/**
 * What the transaction API answers once a caller is let through (TransactionApiAccessTest). Each test says which
 * transactions the use case has for which question; the merchant asked for shows which merchant the controller
 * looked up with (a merchant's own, whatever it asks for).
 */
@WebMvcTest(TransactionController::class)
@Import(SecurityConfig::class)
// beans the controller's context needs; the tests never call them
@MockitoBean(types = [JwtDecoder::class])
class TransactionApiResponseTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var transactionUseCase: TransactionUseCase

    @Test
    fun `a merchant's list has its own transactions with status, link and page information`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("transaction:read"))
        val captured = Transaction(
            paymentId = PaymentId(1002), paymentIntentId = PaymentIntentId(1102), publicPaymentIntentId = "pi_1002",
            merchantAccount = "MARKETPLACE-5",
            buyerId = BuyerId(
                "BUYER-1"
            ),
            orderId = OrderId("ORDER-2"), pspReference = "psp_1002",
            processingModel = ProcessingModel.MARKETPLACE,
            totalAmount = Amount.of(
                3000,
                Currency("EUR")
            ),
            splits = emptyList(),
            authorizedAt = Instant.parse("2026-10-02T10:00:02Z"), capturedAt = Instant.parse("2026-10-02T10:00:07Z")
        )
        val ownTransactions = TransactionFilter(merchantAccount = "MARKETPLACE-5")
        `when`(transactionUseCase.findTransactions(ownTransactions, 0, 20)).thenReturn(listOf(captured))
        `when`(transactionUseCase.countTransactions(ownTransactions)).thenReturn(1)

        mockMvc.get("/api/v1/transactions/merchants/me") { with(merchant) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.items.length()") { value(1) } }
            .andExpect { jsonPath("$.items[0].paymentId") { value("1002") } }
            .andExpect { jsonPath("$.items[0].orderId") { value("ORDER-2") } }
            .andExpect { jsonPath("$.items[0].status") { value("CAPTURED") } }
            .andExpect { jsonPath("$.items[0].totalAmount.quantity") { value(3000) } }
            .andExpect { jsonPath("$.items[0].detailUrl") { value("/api/v1/transactions/merchants/me/1002") } }
            .andExpect { jsonPath("$.items[0].splits") { doesNotExist() } }
            .andExpect { jsonPath("$.totalItems") { value(1) } }
            .andExpect { jsonPath("$.hasNext") { value(false) } }
    }

    @Test
    fun `a merchant's list is its own, whatever merchant it sends along`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("transaction:read"))
        val own = Transaction(
            paymentId = PaymentId(1002), paymentIntentId = PaymentIntentId(1102), publicPaymentIntentId = "pi_1002",
            merchantAccount = "MARKETPLACE-5",
            buyerId = BuyerId(
                "BUYER-1"
            ),
            orderId = OrderId("ORDER-2"), pspReference = "psp_1002",
            processingModel = ProcessingModel.MARKETPLACE,
            totalAmount = Amount.of(
                3000,
                Currency("EUR")
            ),
            splits = emptyList(),
            authorizedAt = Instant.parse("2026-10-02T10:00:02Z")
        )
        // only the MARKETPLACE-5 question has an answer: if the controller passed MARKETPLACE-1 on, the list would be
        // empty
        val ownTransactions = TransactionFilter(merchantAccount = "MARKETPLACE-5")
        `when`(transactionUseCase.findTransactions(ownTransactions, 0, 20)).thenReturn(listOf(own))
        `when`(transactionUseCase.countTransactions(ownTransactions)).thenReturn(1)

        mockMvc.get("/api/v1/transactions/merchants/me?merchantAccount=MARKETPLACE-1") { with(merchant) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.items[0].merchantAccount") { value("MARKETPLACE-5") } }
    }

    @Test
    fun `support lists the transactions of the merchant it names`() {
        val support = jwt().authorities(
            SimpleGrantedAuthority("transaction:read"),
            SimpleGrantedAuthority("merchant:all")
        )
        val ofMarketplace1 = Transaction(
            paymentId = PaymentId(2001), paymentIntentId = PaymentIntentId(2101), publicPaymentIntentId = "pi_2001",
            merchantAccount = "MARKETPLACE-1",
            buyerId = BuyerId(
                "BUYER-1"
            ),
            orderId = OrderId("ORDER-9"), pspReference = "psp_2001",
            processingModel = ProcessingModel.MARKETPLACE,
            totalAmount = Amount.of(
                3000,
                Currency("EUR")
            ),
            splits = emptyList(),
            authorizedAt = Instant.parse("2026-10-02T10:00:03Z")
        )
        val marketplace1 = TransactionFilter(merchantAccount = "MARKETPLACE-1")
        `when`(transactionUseCase.findTransactions(marketplace1, 0, 20)).thenReturn(listOf(ofMarketplace1))
        `when`(transactionUseCase.countTransactions(marketplace1)).thenReturn(1)

        mockMvc.get("/api/v1/transactions/merchants/MARKETPLACE-1") { with(support) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.items[0].merchantAccount") { value("MARKETPLACE-1") } }
            .andExpect {
                jsonPath(
                    "$.items[0].detailUrl"
                ) { value("/api/v1/transactions/merchants/MARKETPLACE-1/2001") }
            }
    }

    @Test
    fun `the list shows each payment's type and card, and filters by type`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("transaction:read"))
        val directSalePaidByMastercard = Transaction(
            paymentId = PaymentId(1004), paymentIntentId = PaymentIntentId(1104), publicPaymentIntentId = "pi_1004",
            merchantAccount = "MARKETPLACE-5",
            buyerId = BuyerId(
                "BUYER-4"
            ),
            orderId = OrderId("ORDER-4"), pspReference = "psp_1004",
            processingModel = ProcessingModel.DIRECT_MERCHANT,
            totalAmount = Amount.of(
                5000,
                Currency("EUR")
            ),
            splits = emptyList(),
            authorizedAt = Instant.parse(
                "2026-10-02T10:00:04Z"
            ),
            cardSummary = CardSummary.of(CardBrand.MASTERCARD, "4444")
        )
        // only the direct-sale question has an answer: the type filter must reach the query
        val directSales = TransactionFilter(
            merchantAccount = "MARKETPLACE-5",
            processingModel = ProcessingModel.DIRECT_MERCHANT
        )
        `when`(transactionUseCase.findTransactions(directSales, 0, 20)).thenReturn(listOf(directSalePaidByMastercard))
        `when`(transactionUseCase.countTransactions(directSales)).thenReturn(1)

        mockMvc.get("/api/v1/transactions/merchants/me?processingModel=DIRECT_MERCHANT") { with(merchant) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.items[0].processingModel") { value("DIRECT_MERCHANT") } }
            .andExpect { jsonPath("$.items[0].card.brand") { value("MASTERCARD") } }
            .andExpect { jsonPath("$.items[0].card.last4") { value("4444") } }
            .andExpect { jsonPath("$.totalItems") { value(1) } }
    }

    @Test
    fun `a page size above 100 gets 400`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("transaction:read"))
        `when`(transactionUseCase.findTransactions(TransactionFilter(merchantAccount = "MARKETPLACE-5"), 0, 101))
            .thenThrow(IllegalArgumentException("size must be between 1 and 100"))

        mockMvc.get("/api/v1/transactions/merchants/me?size=101") { with(merchant) }
            .andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.code") { value("VALIDATION_ERROR") } }
    }

    @Test
    fun `a merchant's transaction detail has buyer, PSP reference and splits`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("transaction:read"))
        val settled = Transaction(
            paymentId = PaymentId(1001), paymentIntentId = PaymentIntentId(1101), publicPaymentIntentId = "pi_1001",
            merchantAccount = "MARKETPLACE-5",
            buyerId = BuyerId(
                "BUYER-1"
            ),
            orderId = OrderId("ORDER-1"), pspReference = "psp_1001",
            processingModel = ProcessingModel.MARKETPLACE, totalAmount = Amount.of(3000, Currency("EUR")),
            splits = listOf(
                PaymentSplit.of(LedgerAccountType.SELLER_PAYABLE, "SELLER-5-1", Amount.of(2800, Currency("EUR"))),
                PaymentSplit.of(
                    LedgerAccountType.MERCHANT_COMMISSION_PAYABLE,
                    "MARKETPLACE-5",
                    Amount.of(200, Currency("EUR"))
                )
            ),
            authorizedAt = Instant.parse("2026-10-02T10:00:01Z"), capturedAt = Instant.parse("2026-10-02T10:00:06Z"),
            settledAt = Instant.parse("2026-10-03T06:00:00Z")
        )
        `when`(transactionUseCase.getTransaction(PaymentId(1001), "MARKETPLACE-5")).thenReturn(settled)

        mockMvc.get("/api/v1/transactions/merchants/me/1001") { with(merchant) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.status") { value("SETTLED") } }
            .andExpect { jsonPath("$.buyerId") { value("BUYER-1") } }
            .andExpect { jsonPath("$.pspReference") { value("psp_1001") } }
            .andExpect { jsonPath("$.splits.length()") { value(2) } }
            .andExpect { jsonPath("$.splits[0].accountType") { value("SELLER_PAYABLE") } }
            .andExpect { jsonPath("$.splits[0].account") { value("SELLER-5-1") } }
            .andExpect { jsonPath("$.splits[0].amount.quantity") { value(2800) } }
    }

    @Test
    fun `another merchant's transaction is not found for a merchant`() {
        val merchant = jwt().jwt {
            it.claim(
                "merchant_id",
                "MARKETPLACE-5"
            )
        }.authorities(SimpleGrantedAuthority("transaction:read"))
        // payment 2001 belongs to MARKETPLACE-1: looked up as MARKETPLACE-5's, it is not there
        `when`(transactionUseCase.getTransaction(PaymentId(2001), "MARKETPLACE-5")).thenReturn(null)

        mockMvc.get("/api/v1/transactions/merchants/me/2001") { with(merchant) }
            .andExpect { status { isNotFound() } }
            .andExpect { jsonPath("$.code") { value("NOT_FOUND") } }
    }

    @Test
    fun `finance reads a transaction of the merchant it names, not under another merchant`() {
        val finance = jwt().authorities(
            SimpleGrantedAuthority("transaction:read"),
            SimpleGrantedAuthority("merchant:all")
        )
        val ofMarketplace1 = Transaction(
            paymentId = PaymentId(2001), paymentIntentId = PaymentIntentId(2101), publicPaymentIntentId = "pi_2001",
            merchantAccount = "MARKETPLACE-1",
            buyerId = BuyerId(
                "BUYER-1"
            ),
            orderId = OrderId("ORDER-9"), pspReference = "psp_2001",
            processingModel = ProcessingModel.MARKETPLACE,
            totalAmount = Amount.of(
                3000,
                Currency("EUR")
            ),
            splits = emptyList(),
            authorizedAt = Instant.parse("2026-10-02T10:00:03Z")
        )
        // 2001 is MARKETPLACE-1's; looked up under MARKETPLACE-5 it is not there
        `when`(transactionUseCase.getTransaction(PaymentId(2001), "MARKETPLACE-1")).thenReturn(ofMarketplace1)
        `when`(transactionUseCase.getTransaction(PaymentId(2001), "MARKETPLACE-5")).thenReturn(null)

        mockMvc.get("/api/v1/transactions/merchants/MARKETPLACE-1/2001") { with(finance) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.merchantAccount") { value("MARKETPLACE-1") } }
            .andExpect { jsonPath("$.status") { value("AUTHORIZED") } }
        mockMvc.get("/api/v1/transactions/merchants/MARKETPLACE-5/2001") { with(finance) }
            .andExpect { status { isNotFound() } }
    }
}
