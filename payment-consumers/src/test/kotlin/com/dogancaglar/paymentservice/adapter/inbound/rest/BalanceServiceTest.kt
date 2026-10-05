package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.domain.model.account.AccountStatus
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.ports.inbound.usecases.AccountBalanceReadUseCase
import com.dogancaglar.paymentservice.ports.outbound.AccountDirectoryPort
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * One rule of BalanceService: a balance has one currency and one total, so two currencies are refused, not added up.
 */
class BalanceServiceTest {

    @Test
    fun `a merchant with a EUR and a USD account is refused, not summed`() {
        val accountDirectory: AccountDirectoryPort = mockk()
        val balances: AccountBalanceReadUseCase = mockk()
        val balanceService = BalanceService(balances, accountDirectory)
        val directInEur =
            AccountProfile(
                "MERCHANT_DIRECT_PAYABLE.MARKETPLACE-5.EUR",
                LedgerAccountType.MERCHANT_DIRECT_PAYABLE,
                "MARKETPLACE-5",
                null,
                Currency("EUR"),
                AccountStatus.ACTIVE
            )
        val commissionInUsd =
            AccountProfile(
                "MERCHANT_COMMISSION_PAYABLE.MARKETPLACE-5.USD",
                LedgerAccountType.MERCHANT_COMMISSION_PAYABLE,
                "MARKETPLACE-5",
                null,
                Currency("USD"),
                AccountStatus.ACTIVE
            )
        every {
            accountDirectory.getAccountProfilesByMaster(LedgerAccountType.MERCHANT_DIRECT_PAYABLE, "MARKETPLACE-5")
        } returns listOf(directInEur)
        every {
            accountDirectory.getAccountProfilesByMaster(LedgerAccountType.MERCHANT_COMMISSION_PAYABLE, "MARKETPLACE-5")
        } returns listOf(commissionInUsd)

        assertThrows<IllegalStateException> { balanceService.getMerchantBalance("MARKETPLACE-5") }
    }
}
