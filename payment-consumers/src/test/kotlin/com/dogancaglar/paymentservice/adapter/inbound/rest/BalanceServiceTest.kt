package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountCategory
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.AccountStatus
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import com.dogancaglar.paymentservice.ports.inbound.usecases.AccountBalanceReadUseCase
import com.dogancaglar.paymentservice.ports.outbound.AccountDirectoryPort
import com.dogancaglar.port.out.web.dto.CurrencyEnum
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BalanceServiceTest {

    private lateinit var accountBalanceReadUseCase: AccountBalanceReadUseCase
    private lateinit var accountDirectory: AccountDirectoryPort
    private lateinit var balanceService: BalanceService

    @BeforeEach
    fun setUp() {
        accountBalanceReadUseCase = mockk(relaxed = true)
        accountDirectory = mockk(relaxed = true)
        balanceService = BalanceService(accountBalanceReadUseCase, accountDirectory)
    }

    @Test
    fun `getSellerBalance should return BalanceDto with correct values`() {
        // Given
        val sellerId = "seller-123"
        val accountCode = "SELLER_PAYABLE.MARKETPLACE-1.seller-123.EUR"
        val balance = 50000L // €500.00

        every { accountDirectory.getAccountProfilesBySubEntity(AccountType.SELLER_PAYABLE, sellerId) } returns listOf(AccountProfile(
            accountCode = accountCode,
            type = AccountType.SELLER_PAYABLE,
            masterAccountCode = "MARKETPLACE-1",
            subEntityId = sellerId,
            currency = Currency("EUR"),
            category = AccountCategory.LIABILITY,
            country = "NL",
            status = AccountStatus.ACTIVE
        ))
        every { accountBalanceReadUseCase.getRealTimeBalance(accountCode) } returns balance

        // When
        val result = balanceService.getSellerBalance(sellerId)

        // Then
        assertNotNull(result)
        assertEquals(balance, result.balance)
        assertEquals(CurrencyEnum.EUR, result.currency)
        assertEquals(accountCode, result.accountCode)
        assertEquals(sellerId, result.sellerId)

        verify(exactly = 1) { accountDirectory.getAccountProfilesBySubEntity(AccountType.SELLER_PAYABLE, sellerId) }
        verify(exactly = 1) { accountBalanceReadUseCase.getRealTimeBalance(accountCode) }
    }

    @Test
    fun `getSellerBalance should handle USD currency correctly`() {
        // Given
        val sellerId = "seller-456"
        val accountCode = "SELLER_PAYABLE.MARKETPLACE-1.seller-456.USD"
        val balance = 123456L // $1,234.56

        every { accountDirectory.getAccountProfilesBySubEntity(AccountType.SELLER_PAYABLE, sellerId) } returns listOf(AccountProfile(
            accountCode = accountCode,
            type = AccountType.SELLER_PAYABLE,
            masterAccountCode = "MARKETPLACE-1",
            subEntityId = sellerId,
            currency = Currency("USD"),
            category = AccountCategory.LIABILITY,
            country = "US",
            status = AccountStatus.ACTIVE
        ))
        every { accountBalanceReadUseCase.getRealTimeBalance(accountCode) } returns balance

        // When
        val result = balanceService.getSellerBalance(sellerId)

        // Then
        assertEquals(balance, result.balance)
        assertEquals(CurrencyEnum.USD, result.currency)
        assertEquals(accountCode, result.accountCode)
        assertEquals(sellerId, result.sellerId)
    }

    @Test
    fun `getSellerBalance should handle zero balance`() {
        // Given
        val sellerId = "seller-789"
        val accountCode = "SELLER_PAYABLE.MARKETPLACE-1.seller-789.EUR"
        val balance = 0L

        every { accountDirectory.getAccountProfilesBySubEntity(AccountType.SELLER_PAYABLE, sellerId) } returns listOf(AccountProfile(
            accountCode = accountCode,
            type = AccountType.SELLER_PAYABLE,
            masterAccountCode = "MARKETPLACE-1",
            subEntityId = sellerId,
            currency = Currency("EUR"),
            category = AccountCategory.LIABILITY,
            country = "NL",
            status = AccountStatus.ACTIVE
        ))
        every { accountBalanceReadUseCase.getRealTimeBalance(accountCode) } returns balance

        // When
        val result = balanceService.getSellerBalance(sellerId)

        // Then
        assertEquals(0L, result.balance)
        assertEquals(CurrencyEnum.EUR, result.currency)
    }

    @Test
    fun `getSellerBalance should handle negative balance`() {
        // Given
        val sellerId = "seller-999"
        val accountCode = "SELLER_PAYABLE.MARKETPLACE-1.seller-999.EUR"
        val balance = -5000L // -€50.00 (overdraft scenario)

        every { accountDirectory.getAccountProfilesBySubEntity(AccountType.SELLER_PAYABLE, sellerId) } returns listOf(AccountProfile(
            accountCode = accountCode,
            type = AccountType.SELLER_PAYABLE,
            masterAccountCode = "MARKETPLACE-1",
            subEntityId = sellerId,
            currency = Currency("EUR"),
            category = AccountCategory.LIABILITY,
            country = "NL",
            status = AccountStatus.ACTIVE
        ))
        every { accountBalanceReadUseCase.getRealTimeBalance(accountCode) } returns balance

        // When
        val result = balanceService.getSellerBalance(sellerId)

        // Then
        assertEquals(-5000L, result.balance)
        assertEquals(CurrencyEnum.EUR, result.currency)
    }

    @Test
    fun `getSellerBalance should throw when account profile not found`() {
        // Given
        val sellerId = "non-existent-seller"

        every { accountDirectory.getAccountProfilesBySubEntity(AccountType.SELLER_PAYABLE, sellerId) } returns emptyList()

        // When/Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            balanceService.getSellerBalance(sellerId)
        }
        assertTrue(exception.message?.contains("Account not found") == true)

        verify(exactly = 1) { accountDirectory.getAccountProfilesBySubEntity(AccountType.SELLER_PAYABLE, sellerId) }
        verify(exactly = 0) { accountBalanceReadUseCase.getRealTimeBalance(any()) }
    }

    @Test
    fun `getSellerBalance should handle GBP currency correctly`() {
        // Given
        val sellerId = "seller-GBP"
        val accountCode = "SELLER_PAYABLE.MARKETPLACE-1.seller-GBP.GBP"
        val balance = 75000L // £750.00

        every { accountDirectory.getAccountProfilesBySubEntity(AccountType.SELLER_PAYABLE, sellerId) } returns listOf(AccountProfile(
            accountCode = accountCode,
            type = AccountType.SELLER_PAYABLE,
            masterAccountCode = "MARKETPLACE-1",
            subEntityId = sellerId,
            currency = Currency("GBP"),
            category = AccountCategory.LIABILITY,
            country = "GB",
            status = AccountStatus.ACTIVE
        ))
        every { accountBalanceReadUseCase.getRealTimeBalance(accountCode) } returns balance

        // When
        val result = balanceService.getSellerBalance(sellerId)

        // Then
        assertEquals(balance, result.balance)
        assertEquals(CurrencyEnum.GBP, result.currency)
        assertEquals(accountCode, result.accountCode)
        assertEquals(sellerId, result.sellerId)
    }

    @Test
    fun `getSellerBalance should use real-time balance from use case`() {
        // Given
        val sellerId = "seller-123"
        val accountCode = "SELLER_PAYABLE.MARKETPLACE-1.seller-123.EUR"
        val realTimeBalance = 123456L

        every { accountDirectory.getAccountProfilesBySubEntity(AccountType.SELLER_PAYABLE, sellerId) } returns listOf(AccountProfile(
            accountCode = accountCode,
            type = AccountType.SELLER_PAYABLE,
            masterAccountCode = "MARKETPLACE-1",
            subEntityId = sellerId,
            currency = Currency("EUR"),
            category = AccountCategory.LIABILITY,
            country = "NL",
            status = AccountStatus.ACTIVE
        ))
        every { accountBalanceReadUseCase.getRealTimeBalance(accountCode) } returns realTimeBalance

        // When
        val result = balanceService.getSellerBalance(sellerId)

        // Then
        assertEquals(realTimeBalance, result.balance)
        verify(exactly = 1) { accountBalanceReadUseCase.getRealTimeBalance(accountCode) }
        // Verify it's using getRealTimeBalance, not getStrongBalance
        verify(exactly = 0) { accountBalanceReadUseCase.getStrongBalance(any()) }
    }

    @Test
    fun `getSellerBalance should throw when the seller has accounts in more than one currency`() {
        // Given
        val sellerId = "seller-123"
        val eurProfile = AccountProfile(
            accountCode = "SELLER_PAYABLE.MARKETPLACE-1.seller-123.EUR",
            type = AccountType.SELLER_PAYABLE,
            masterAccountCode = "MARKETPLACE-1",
            subEntityId = sellerId,
            currency = Currency("EUR"),
            category = AccountCategory.LIABILITY,
            country = "NL",
            status = AccountStatus.ACTIVE
        )
        val usdProfile = eurProfile.copy(
            accountCode = "SELLER_PAYABLE.MARKETPLACE-1.seller-123.USD",
            currency = Currency("USD")
        )
        every { accountDirectory.getAccountProfilesBySubEntity(AccountType.SELLER_PAYABLE, sellerId) } returns listOf(eurProfile, usdProfile)

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            balanceService.getSellerBalance(sellerId)
        }
        verify(exactly = 0) { accountBalanceReadUseCase.getRealTimeBalance(any()) }
    }
}
