package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountCategory
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.AccountStatus
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import com.dogancaglar.paymentservice.ports.inbound.usecases.AccountBalanceReadUseCase
import com.dogancaglar.paymentservice.ports.outbound.AccountDirectoryPort
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Rules of BalanceService not covered by BalanceApiSecurityTest (which covers seller, merchant,
 * ownership and not-found through HTTP): one response has one currency and one total.
 */
class BalanceServiceTest {

    private val accountDirectory: AccountDirectoryPort = mockk()
    private val balanceReads: AccountBalanceReadUseCase = mockk()
    private val balanceService = BalanceService(balanceReads, accountDirectory)

    @Test
    fun `merchant with only a direct-sales account gets that one line`() {
        every { accountDirectory.getAccountProfilesByMaster(AccountType.MERCHANT_DIRECT_PAYABLE, "MARKETPLACE-5") } returns
            listOf(profile("MERCHANT_DIRECT_PAYABLE.MARKETPLACE-5.EUR", AccountType.MERCHANT_DIRECT_PAYABLE, "EUR"))
        every { accountDirectory.getAccountProfilesByMaster(AccountType.MERCHANT_COMMISSION_PAYABLE, "MARKETPLACE-5") } returns emptyList()
        every { balanceReads.getRealTimeBalance("MERCHANT_DIRECT_PAYABLE.MARKETPLACE-5.EUR") } returns 4950

        val balance = balanceService.getMerchantBalance("MARKETPLACE-5")

        assertEquals(4950, balance.total)
        assertEquals(1, balance.accounts.size)
    }

    @Test
    fun `accounts in several currencies are refused, not summed`() {
        every { accountDirectory.getAccountProfilesByMaster(AccountType.MERCHANT_DIRECT_PAYABLE, "MARKETPLACE-5") } returns
            listOf(profile("MERCHANT_DIRECT_PAYABLE.MARKETPLACE-5.EUR", AccountType.MERCHANT_DIRECT_PAYABLE, "EUR"))
        every { accountDirectory.getAccountProfilesByMaster(AccountType.MERCHANT_COMMISSION_PAYABLE, "MARKETPLACE-5") } returns
            listOf(profile("MERCHANT_COMMISSION_PAYABLE.MARKETPLACE-5.USD", AccountType.MERCHANT_COMMISSION_PAYABLE, "USD"))

        assertThrows<IllegalStateException> { balanceService.getMerchantBalance("MARKETPLACE-5") }
    }

    private fun profile(code: String, type: AccountType, currency: String): AccountProfile =
        AccountProfile(
            accountCode = code,
            type = type,
            masterAccountCode = "MARKETPLACE-5",
            subEntityId = null,
            currency = Currency(currency),
            category = AccountCategory.LIABILITY,
            country = "NL",
            status = AccountStatus.ACTIVE
        )
}
