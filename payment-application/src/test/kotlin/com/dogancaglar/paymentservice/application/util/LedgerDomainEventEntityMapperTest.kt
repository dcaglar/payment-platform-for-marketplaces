package com.dogancaglar.paymentservice.application.util

import com.dogancaglar.paymentservice.application.events.JournalEntryEventData
import com.dogancaglar.paymentservice.application.events.PostingDirection
import com.dogancaglar.paymentservice.application.events.PostingEventData
import com.dogancaglar.paymentservice.application.util.LedgerDomainEventEntityMapper.toDomain
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.Account
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.ledger.JournalType
import com.dogancaglar.paymentservice.domain.model.ledger.Posting
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId

class LedgerDomainEventEntityMapperTest {

    private val fixedInstant = Instant.parse("2025-11-07T16:20:00Z")

    private fun sampleJournalEntry(): JournalEntry {
        val merchantAccount = Account.Companion.mock(AccountType.MERCHANT_GROSS_CAPTURE_SUSPENSE, "SELLER-333", "EUR")
        val pspReceivable = Account.Companion.mock(AccountType.PSP_RECEIVABLES, "GLOBAL", "EUR")
        val debitPosting = Posting.Debit.create(merchantAccount, Amount.Companion.of(1000, Currency("EUR")))
        val creditPosting = Posting.Credit.create(pspReceivable, Amount.Companion.of(1000, Currency("EUR")))

        return JournalEntry.JournalFactory.rehytrate(
            id = "journal-123",
            globalJournalEntryId = 100L,
            txType = JournalType.CAPTURE,
            name = "capture",
            paymentId = PaymentId(555L),
            txId = TxId(666L),
            postings = listOf(debitPosting, creditPosting),
            reason = "TestReason"
        )
    }

    private fun sampleJournalEntryEventData(): JournalEntryEventData {
        val debitEvent = PostingEventData.create(
            accountCode = "MERCHANT_GROSS_CAPTURE_SUSPENSE.SELLER-333.EUR",
            accountType = AccountType.MERCHANT_GROSS_CAPTURE_SUSPENSE,
            amount = 1000,
            currency = "EUR",
            direction = PostingDirection.DEBIT
        )
        val creditEvent = PostingEventData.create(
            accountCode = "PSP_RECEIVABLES.GLOBAL.EUR",
            accountType = AccountType.PSP_RECEIVABLES,
            amount = 1000,
            currency = "EUR",
            direction = PostingDirection.CREDIT
        )

        return JournalEntryEventData.create(
            journalEntryId = "journal-9876",
            journalType = JournalType.CAPTURE,
            journalName = "capture",
            paymentId = 555L,
            txId = 666L,
            createdAt = fixedInstant.plusSeconds(2400),
            postings = listOf(debitEvent, creditEvent),
            globalJournalEntryId = 200L,
            reason = "Test reason"
        )
    }

    @Test
    fun `toLedgerEntryEventData maps domain journal entry to event DTO`() {
        val journalEntry = sampleJournalEntry()

        val eventData = LedgerDomainEventEntityMapper.toLedgerEntryEventData(journalEntry)

        Assertions.assertEquals(journalEntry.id, eventData.journalEntryId)
        Assertions.assertEquals(journalEntry.journalType, eventData.journalType)

        val postingEvent = eventData.postings.first()
        val postingDomain = journalEntry.postings.first()

        Assertions.assertEquals(postingDomain.account.accountCode, postingEvent.accountCode)
        Assertions.assertEquals(postingDomain.account.type, postingEvent.accountType)
        Assertions.assertEquals(postingDomain.amount.quantity, postingEvent.amount)
        Assertions.assertEquals(postingDomain.amount.currency.currencyCode, postingEvent.currency)
        assertEquals(PostingDirection.DEBIT, postingEvent.direction)
    }

    @Test
    fun `PostingEventData toDomain round-trips account entity without duplicating currency`() {
        val postingEvent = PostingEventData.create(
            accountCode = "MERCHANT_GROSS_CAPTURE_SUSPENSE.SELLER-333.EUR",
            accountType = AccountType.MERCHANT_GROSS_CAPTURE_SUSPENSE,
            amount = 1000,
            currency = "EUR",
            direction = PostingDirection.CREDIT
        )

        val postingDomain = postingEvent.toDomain()

        Assertions.assertEquals("MERCHANT_GROSS_CAPTURE_SUSPENSE.SELLER-333.EUR", postingDomain.account.accountCode)

        Assertions.assertEquals(AccountType.MERCHANT_GROSS_CAPTURE_SUSPENSE, postingDomain.account.type)
        Assertions.assertEquals("EUR", postingDomain.account.currency.currencyCode)
        Assertions.assertTrue(postingDomain is Posting.Credit)
    }

    @Test
    fun `toDomain recreates journal entry with postings`() {
        val eventData = sampleJournalEntryEventData()

        val domain = toDomain(eventData)

        assertEquals(eventData.journalEntryId, domain.id)
        assertEquals(eventData.journalType, domain.journalType)

        val postings = domain.postings
        Assertions.assertEquals(2, postings.size)

        val debitPosting = postings.first { it is Posting.Debit }
        Assertions.assertEquals("MERCHANT_GROSS_CAPTURE_SUSPENSE.SELLER-333.EUR", debitPosting.account.accountCode)
        Assertions.assertEquals(AccountType.MERCHANT_GROSS_CAPTURE_SUSPENSE, debitPosting.account.type)

        Assertions.assertEquals("EUR", debitPosting.account.currency.currencyCode)
        Assertions.assertEquals(1000, debitPosting.amount.quantity)

        val creditPosting = postings.first { it is Posting.Credit }
        Assertions.assertEquals("PSP_RECEIVABLES.GLOBAL.EUR", creditPosting.account.accountCode)
        Assertions.assertEquals(AccountType.PSP_RECEIVABLES, creditPosting.account.type)

        Assertions.assertEquals("EUR", creditPosting.account.currency.currencyCode)
        Assertions.assertEquals(1000, creditPosting.amount.quantity)
    }
}