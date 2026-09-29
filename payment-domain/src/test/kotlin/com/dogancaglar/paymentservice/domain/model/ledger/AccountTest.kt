package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.model.common.Currency
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AccountTest {

    @Test
    fun `create should create account with correct type and accountCode`() {
        val account = Account.create(AccountType.CAPTURE_SUSPENSE, "CAPTURE_SUSPENSE.seller-123.EUR")

        assertEquals(AccountType.CAPTURE_SUSPENSE, account.type)
        assertEquals("CAPTURE_SUSPENSE.seller-123.EUR", account.accountCode)
        assertEquals("EUR", account.currency.currencyCode) // Default currency
        assertEquals(AuthType.SALE, account.authType) // Default authType
    }

    @Test
    fun `create should default to EUR currency`() {
        val account = Account.create(AccountType.PLATFORM_CASH, "PLATFORM_CASH.GLOBAL.EUR")

        assertEquals(Currency("EUR"), account.currency)
        assertEquals("EUR", account.currency.currencyCode)
    }

    @Test
    fun `create should default to SALE authType`() {
        val account = Account.create(AccountType.CAPTURE_SUSPENSE, "CAPTURE_SUSPENSE.seller-456.EUR")

        assertEquals(AuthType.SALE, account.authType)
    }

    @Test
    fun `accountCode should follow format passed in`() {
        val account = Account.create(AccountType.CAPTURE_SUSPENSE, "seller-789.EUR")

        assertEquals("seller-789.EUR", account.accountCode)
    }

    @Test
    fun `create should reject empty accountCode`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Account.create(AccountType.CAPTURE_SUSPENSE, "")
        }
        assertTrue(exception.message?.contains("Account code cant be empty") == true)
    }

    @Test
    fun `create should reject blank accountCode`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Account.create(AccountType.PLATFORM_CASH, "   ")
        }
        assertTrue(exception.message?.contains("Account code cant be empty") == true)
    }

    @Test
    fun `fromProfile should create account with profile properties`() {
        val profile = AccountProfile(
            accountCode = "CAPTURE_SUSPENSE.MARKETPLACE-1.EUR",
            type = AccountType.CAPTURE_SUSPENSE,
            masterAccountCode = "MARKETPLACE-1",
            subEntityId = null,
            currency = Currency("EUR"),
            category = AccountCategory.LIABILITY,
            country = "NL",
            status = AccountStatus.ACTIVE
        )

        val account = Account.fromProfile(profile)

        assertEquals(AccountType.CAPTURE_SUSPENSE, account.type)
        assertEquals(Currency("EUR"), account.currency)
        assertEquals("CAPTURE_SUSPENSE.MARKETPLACE-1.EUR", account.accountCode)
    }

    @Test
    fun `fromProfile should handle different currencies`() {
        val profile = AccountProfile(
            accountCode = "PLATFORM_CASH.GLOBAL.USD",
            type = AccountType.PLATFORM_CASH,
            masterAccountCode = "GLOBAL",
            subEntityId = null,
            currency = Currency("USD"),
            category = AccountCategory.ASSET,
            country = null,
            status = AccountStatus.ACTIVE
        )

        val account = Account.fromProfile(profile)

        assertEquals(Currency("USD"), account.currency)
        assertEquals("PLATFORM_CASH.GLOBAL.USD", account.accountCode)
    }

    @Test
    fun `mock should create account with test defaults`() {
        val account = Account.mock(AccountType.PSP_RECEIVABLE)

        assertEquals(AccountType.PSP_RECEIVABLE, account.type)
        assertEquals("PSP_RECEIVABLE.GLOBAL.EUR", account.accountCode)
        assertEquals("EUR", account.currency.currencyCode)
    }

    @Test
    fun `isDebitAccount should return true for debit account types`() {
        val cashAccount = Account.create(AccountType.PLATFORM_CASH, "GLOBAL")
        val pspReceivableAccount = Account.create(AccountType.PSP_RECEIVABLE, "GLOBAL")
        val authReceivableAccount = Account.create(AccountType.AUTH_RECEIVABLE, "GLOBAL")
        val schemeFeesAccount = Account.create(AccountType.PSP_FEE_EXPENSE, "GLOBAL")

        assertTrue(cashAccount.isDebitAccount())
        assertTrue(pspReceivableAccount.isDebitAccount())
        assertTrue(authReceivableAccount.isDebitAccount())
        assertTrue(schemeFeesAccount.isDebitAccount())
    }

    @Test
    fun `isDebitAccount should return false for credit account types`() {
        val merchantAccount = Account.create(AccountType.CAPTURE_SUSPENSE, "seller-1")
        val authLiabilityAccount = Account.create(AccountType.AUTH_LIABILITY, "GLOBAL")
        val processingFeeAccount = Account.create(AccountType.PLATFORM_REVENUE, "GLOBAL")

        assertFalse(merchantAccount.isDebitAccount())
        assertFalse(authLiabilityAccount.isDebitAccount())
        assertFalse(processingFeeAccount.isDebitAccount())
    }

    @Test
    fun `isCreditAccount should return true for credit account types`() {
        val merchantAccount = Account.create(AccountType.CAPTURE_SUSPENSE, "seller-2")
        val authLiabilityAccount = Account.create(AccountType.AUTH_LIABILITY, "GLOBAL")
        val processingFeeAccount = Account.create(AccountType.PLATFORM_REVENUE, "GLOBAL")

        assertTrue(merchantAccount.isCreditAccount())
        assertTrue(authLiabilityAccount.isCreditAccount())
        assertTrue(processingFeeAccount.isCreditAccount())
    }

    @Test
    fun `isCreditAccount should return false for debit account types`() {
        val cashAccount = Account.create(AccountType.PLATFORM_CASH, "GLOBAL")
        val pspReceivableAccount = Account.create(AccountType.PSP_RECEIVABLE, "GLOBAL")
        val authReceivableAccount = Account.create(AccountType.AUTH_RECEIVABLE, "GLOBAL")
        val schemeFeesAccount = Account.create(AccountType.PSP_FEE_EXPENSE, "GLOBAL")

        assertFalse(cashAccount.isCreditAccount())
        assertFalse(pspReceivableAccount.isCreditAccount())
        assertFalse(authReceivableAccount.isCreditAccount())
        assertFalse(schemeFeesAccount.isCreditAccount())
    }

    @Test
    fun `all account types should have valid normalBalance`() {
        AccountType.values().forEach { accountType ->
            val account = Account.create(accountType, "test")
            assertTrue(account.isDebitAccount() || account.isCreditAccount(),
                "Account type ${accountType} must have either DEBIT or CREDIT normal balance")
        }
    }

    @Test
    fun `all account types should have valid category`() {
        AccountType.values().forEach { accountType ->
            val account = Account.create(accountType, "test")
            assertNotNull(account.type.category, 
                "Account type ${accountType} must have a valid category")
        }
    }
}
