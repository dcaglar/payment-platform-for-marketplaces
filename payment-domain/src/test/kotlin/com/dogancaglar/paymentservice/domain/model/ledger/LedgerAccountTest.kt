package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.exception.LedgerDomainException
import com.dogancaglar.paymentservice.domain.model.account.AccountStatus
import com.dogancaglar.paymentservice.domain.model.common.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerAccountTest {

    private val eur = Currency("EUR")

    // --------------------------------------------------------------- derived account code

    @Test
    fun `platform account code is TYPE dot GLOBAL dot CURRENCY`() {
        val account = LedgerAccount.createNew(LedgerAccountType.PSP_RECEIVABLE, "GLOBAL", null, eur)

        assertEquals("PSP_RECEIVABLE.GLOBAL.EUR", account.accountCode)
    }

    @Test
    fun `merchant account code is TYPE dot MERCHANT dot CURRENCY`() {
        val account = LedgerAccount.createNew(LedgerAccountType.CAPTURE_SUSPENSE, "MARKETPLACE-5", null, eur)

        assertEquals("CAPTURE_SUSPENSE.MARKETPLACE-5.EUR", account.accountCode)
    }

    @Test
    fun `seller account code is TYPE dot MERCHANT dot SELLER dot CURRENCY`() {
        val account = LedgerAccount.createNew(LedgerAccountType.SELLER_PAYABLE, "MARKETPLACE-5", "SELLER-5-1", eur)

        assertEquals("SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR", account.accountCode)
    }

    @Test
    fun `a new account is active`() {
        val account = LedgerAccount.createNew(LedgerAccountType.PLATFORM_CASH, "GLOBAL", null, eur)

        assertEquals(AccountStatus.ACTIVE, account.status)
    }

    // --------------------------------------------------------------- owner level rules

    @Test
    fun `platform type must belong to GLOBAL`() {
        assertFailsWith<LedgerDomainException.InvariantViolationException> {
            LedgerAccount.createNew(LedgerAccountType.PLATFORM_CASH, "MARKETPLACE-5", null, eur)
        }
    }

    @Test
    fun `merchant type cannot belong to GLOBAL`() {
        assertFailsWith<LedgerDomainException.InvariantViolationException> {
            LedgerAccount.createNew(LedgerAccountType.AUTH_RECEIVABLE, "GLOBAL", null, eur)
        }
    }

    @Test
    fun `merchant type cannot have a seller`() {
        assertFailsWith<LedgerDomainException.InvariantViolationException> {
            LedgerAccount.createNew(LedgerAccountType.MERCHANT_COMMISSION_PAYABLE, "MARKETPLACE-5", "SELLER-5-1", eur)
        }
    }

    @Test
    fun `seller type needs a seller`() {
        assertFailsWith<LedgerDomainException.InvariantViolationException> {
            LedgerAccount.createNew(LedgerAccountType.SELLER_PAYABLE, "MARKETPLACE-5", null, eur)
        }
    }

    @Test
    fun `owner code cannot contain the code separator`() {
        assertFailsWith<LedgerDomainException.InvariantViolationException> {
            LedgerAccount.createNew(LedgerAccountType.CAPTURE_SUSPENSE, "MARKET.PLACE", null, eur)
        }
    }

    // --------------------------------------------------------------- rebuilding

    @Test
    fun `fromProfile rebuilds the account from its directory row`() {
        val profile = AccountProfile(
            accountCode = "SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR",
            type = LedgerAccountType.SELLER_PAYABLE,
            masterAccountCode = "MARKETPLACE-5",
            subEntityId = "SELLER-5-1",
            currency = eur,
            status = AccountStatus.SUSPENDED
        )

        val account = LedgerAccount.fromProfile(profile)

        assertEquals("SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR", account.accountCode)
        assertEquals("MARKETPLACE-5", account.ownerCode)
        assertEquals("SELLER-5-1", account.sellerCode)
        assertEquals(AccountStatus.SUSPENDED, account.status)
    }

    @Test
    fun `fromCode rebuilds a merchant account from its code`() {
        val account = LedgerAccount.fromCode(LedgerAccountType.CAPTURE_SUSPENSE, "CAPTURE_SUSPENSE.MARKETPLACE-5.USD")

        assertEquals("MARKETPLACE-5", account.ownerCode)
        assertNull(account.sellerCode)
        assertEquals(Currency("USD"), account.currency)
        assertEquals("CAPTURE_SUSPENSE.MARKETPLACE-5.USD", account.accountCode)
    }

    @Test
    fun `fromCode rebuilds a seller account from its code`() {
        val account = LedgerAccount.fromCode(
            LedgerAccountType.SELLER_PAYABLE,
            "SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR"
        )

        assertEquals("MARKETPLACE-5", account.ownerCode)
        assertEquals("SELLER-5-1", account.sellerCode)
        assertEquals("SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR", account.accountCode)
    }

    @Test
    fun `fromCode rejects a code of another type`() {
        assertFailsWith<LedgerDomainException.InvariantViolationException> {
            LedgerAccount.fromCode(LedgerAccountType.SELLER_PAYABLE, "CAPTURE_SUSPENSE.MARKETPLACE-5.EUR")
        }
    }

    @Test
    fun `fromCode rejects a code with too few parts`() {
        assertFailsWith<LedgerDomainException.InvariantViolationException> {
            LedgerAccount.fromCode(LedgerAccountType.PLATFORM_CASH, "PLATFORM_CASH.EUR")
        }
    }

    // --------------------------------------------------------------- normal balance comes from the type

    @Test
    fun `debit-normal types are debit accounts`() {
        val cash = LedgerAccount.createNew(LedgerAccountType.PLATFORM_CASH, "GLOBAL", null, eur)
        val authReceivable = LedgerAccount.createNew(LedgerAccountType.AUTH_RECEIVABLE, "MARKETPLACE-5", null, eur)

        assertTrue(cash.isDebitAccount())
        assertFalse(cash.isCreditAccount())
        assertTrue(authReceivable.isDebitAccount())
    }

    @Test
    fun `credit-normal types are credit accounts`() {
        val sellerPayable = LedgerAccount.createNew(
            LedgerAccountType.SELLER_PAYABLE,
            "MARKETPLACE-5",
            "SELLER-5-1",
            eur
        )
        val revenue = LedgerAccount.createNew(LedgerAccountType.PLATFORM_REVENUE, "GLOBAL", null, eur)

        assertTrue(sellerPayable.isCreditAccount())
        assertFalse(sellerPayable.isDebitAccount())
        assertTrue(revenue.isCreditAccount())
    }
}
