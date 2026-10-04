package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.config.SecurityConfig
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.domain.model.ledger.Posting
import com.dogancaglar.paymentservice.domain.model.ledger.SettleStatus
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.ledger.TxStatus
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import com.dogancaglar.paymentservice.ports.inbound.usecases.TxUseCase
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

/** What finance gets for a payment and a tx once let through (TxApiAccessTest): txs, journal entries, postings. */
@WebMvcTest(TxController::class)
@Import(SecurityConfig::class)
// beans the controller's context needs; the tests never call them
@MockitoBean(types = [JwtDecoder::class])
class TxApiResponseTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var txUseCase: TxUseCase

    @Test
    fun `a payment shows its txs in order and its journal entries, with their postings and totals`() {
        val finance = jwt().authorities(SimpleGrantedAuthority("ledger:read"), SimpleGrantedAuthority("merchant:all"))
        val authorization = Tx.AuthorizationTx(
            txId = TxId(5001),
            paymentId = PaymentId(1001),
            paymentIntentId = PaymentIntentId(1101),
            acquirerReference = "psp_1001",
            amount = Amount.of(3000, Currency("EUR")),
            createdAt = Instant.parse("2026-10-02T10:00:00Z")
        )
        val capture = Tx.CaptureTx(
            txId = TxId(
                5002
            ),
            paymentId = PaymentId(1001), paymentIntentId = PaymentIntentId(1101), authorizationTxId = TxId(5001),
            acquirerReference = "psp_1001", amount = Amount.of(3000, Currency("EUR")), status = TxStatus.SUCCESS,
            settleStatus = SettleStatus.MATCHED, createdAt = Instant.parse("2026-10-02T10:00:02Z")
        )
        // the allocation: 1400 from capture suspense to SELLER-5-1, 100 to the commission (no tx)
        val allocation = JournalEntry.rehytrate(
            id = "INTERNAL_TRANSFER:1",
            globalJournalEntryId = 9001,
            txType = JournalType.INTERNAL_TRANSFER,
            name = "Allocation",
            paymentId = PaymentId(1001),
            txId = null,
            reason = null,
            postings = listOf(
                Posting.Debit.create(
                    LedgerAccount.fromCode(LedgerAccountType.CAPTURE_SUSPENSE, "CAPTURE_SUSPENSE.MARKETPLACE-5.EUR"),
                    Amount.of(1500, Currency("EUR"))
                ),
                Posting.Credit.create(
                    LedgerAccount.fromCode(
                        LedgerAccountType.SELLER_PAYABLE,
                        "SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR"
                    ),
                    Amount.of(1400, Currency("EUR"))
                ),
                Posting.Credit.create(
                    LedgerAccount.fromCode(
                        LedgerAccountType.MERCHANT_COMMISSION_PAYABLE,
                        "MERCHANT_COMMISSION_PAYABLE.MARKETPLACE-5.EUR"
                    ),
                    Amount.of(100, Currency("EUR"))
                )
            )
        )
        `when`(txUseCase.findTxsOfPayment(PaymentId(1001), "MARKETPLACE-5")).thenReturn(listOf(authorization, capture))
        `when`(txUseCase.findJournalEntriesOfPayment(PaymentId(1001), "MARKETPLACE-5")).thenReturn(listOf(allocation))

        mockMvc.get("/api/v1/txs/merchants/MARKETPLACE-5/payments/1001") { with(finance) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.txs.length()") { value(2) } }
            .andExpect { jsonPath("$.txs[0].txType") { value("AUTHORIZATION") } }
            .andExpect { jsonPath("$.txs[0].detailUrl") { value("/api/v1/txs/merchants/MARKETPLACE-5/5001") } }
            .andExpect { jsonPath("$.txs[1].txType") { value("CAPTURE") } }
            .andExpect { jsonPath("$.txs[1].parentTxId") { value("5001") } }
            .andExpect { jsonPath("$.txs[1].settleStatus") { value("MATCHED") } }
            .andExpect { jsonPath("$.txs[1].journalEntries") { doesNotExist() } }
            .andExpect { jsonPath("$.journalEntries.length()") { value(1) } }
            .andExpect { jsonPath("$.journalEntries[0].journalType") { value("INTERNAL_TRANSFER") } }
            .andExpect { jsonPath("$.journalEntries[0].postings.length()") { value(3) } }
            .andExpect {
                jsonPath(
                    "$.journalEntries[0].postings[1].accountCode"
                ) { value("SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR") }
            }
            .andExpect { jsonPath("$.journalEntries[0].postings[1].direction") { value("CREDIT") } }
            .andExpect { jsonPath("$.journalEntries[0].totalDebit.quantity") { value(1500) } }
            .andExpect { jsonPath("$.journalEntries[0].totalCredit.quantity") { value(1500) } }
    }

    @Test
    fun `a tx shows its journal entries`() {
        val finance = jwt().authorities(SimpleGrantedAuthority("ledger:read"), SimpleGrantedAuthority("merchant:all"))
        val authorization = Tx.AuthorizationTx(
            txId = TxId(5001),
            paymentId = PaymentId(1001),
            paymentIntentId = PaymentIntentId(1101),
            acquirerReference = "psp_1001",
            amount = Amount.of(3000, Currency("EUR")),
            createdAt = Instant.parse("2026-10-02T10:00:00Z")
        )
        val authHold = JournalEntry.rehytrate(
            id = "AUTH:pi_1001",
            globalJournalEntryId = 9000,
            txType = JournalType.AUTHORIZATION,
            name = "Authorization hold",
            paymentId = PaymentId(1001),
            txId = TxId(5001),
            reason = null,
            postings = listOf(
                Posting.Debit.create(
                    LedgerAccount.fromCode(LedgerAccountType.AUTH_RECEIVABLE, "AUTH_RECEIVABLE.MARKETPLACE-5.EUR"),
                    Amount.of(3000, Currency("EUR"))
                ),
                Posting.Credit.create(
                    LedgerAccount.fromCode(LedgerAccountType.AUTH_LIABILITY, "AUTH_LIABILITY.MARKETPLACE-5.EUR"),
                    Amount.of(3000, Currency("EUR"))
                )
            )
        )
        `when`(txUseCase.getTx(TxId(5001), "MARKETPLACE-5")).thenReturn(authorization)
        `when`(txUseCase.findJournalEntriesOfTx(TxId(5001), "MARKETPLACE-5")).thenReturn(listOf(authHold))

        mockMvc.get("/api/v1/txs/merchants/MARKETPLACE-5/5001") { with(finance) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.txType") { value("AUTHORIZATION") } }
            .andExpect { jsonPath("$.paymentId") { value("1001") } }
            .andExpect { jsonPath("$.acquirerReference") { value("psp_1001") } }
            .andExpect { jsonPath("$.journalEntries.length()") { value(1) } }
            .andExpect { jsonPath("$.journalEntries[0].txId") { value("5001") } }
            .andExpect { jsonPath("$.journalEntries[0].postings[0].accountType") { value("AUTH_RECEIVABLE") } }
            .andExpect { jsonPath("$.journalEntries[0].postings[0].direction") { value("DEBIT") } }
            .andExpect { jsonPath("$.journalEntries[0].postings[0].amount.quantity") { value(3000) } }
            .andExpect { jsonPath("$.journalEntries[0].postings[1].direction") { value("CREDIT") } }
            .andExpect { jsonPath("$.journalEntries[0].totalDebit.quantity") { value(3000) } }
    }
}
