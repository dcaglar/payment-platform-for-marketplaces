package com.dogancaglar.paymentservice.domain.model.account

import com.dogancaglar.paymentservice.domain.exception.AccountDomainException
import com.dogancaglar.paymentservice.domain.exception.PaymentDomainException
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The merchant and seller accounts' own rules. */
class AccountGenerationTest {

    private val eur = Currency("EUR")

    @Test
    fun `platform fee must be in the merchant currency`() {
        assertFailsWith<AccountDomainException.InvariantViolationException> {
            merchant(fee = PlatformFee.of(Amount.of(30, Currency("USD")), 150))
        }
    }

    @Test
    fun `platform fee basis points must be between 0 and 10000`() {
        assertFailsWith<AccountDomainException.InvariantViolationException> { PlatformFee.of(Amount.zero(eur), -1) }
        assertFailsWith<AccountDomainException.InvariantViolationException> { PlatformFee.of(Amount.zero(eur), 10_001) }
    }

    @Test
    fun `a seller cannot use its merchant's code`() {
        assertFailsWith<AccountDomainException.InvariantViolationException> {
            SellerAccount.createNew("MARKETPLACE-5", "MARKETPLACE-5")
        }
    }

    private fun merchant(
        code: String = "MARKETPLACE-5",
        fee: PlatformFee = PlatformFee.of(Amount.of(50, eur), 0)
    ): MerchantAccount =
        MerchantAccount.createNew(
            accountCode = code,
            legalName = "Marketplace Five B.V.",
            address = Address.of("Damrak 1", null, "Amsterdam", "1012 LG", "NL"),
            industry = "5399",
            currency = eur,
            platformFee = fee
        )

    @Test
    fun `merchant is auto-captured and not auto-settled by default`() {
        val merchant = merchant()

        assertEquals(true, merchant.isAutoCaptured)
        assertEquals(false, merchant.isAutoSettled)
    }

    @Test
    fun `the platform fee is the fixed part plus the percentage of the payment, rounded down to the cent`() {
        val eur = Currency("EUR")
        val fixedPlusFivePercent = PlatformFee.of(Amount.of(50, eur), 500)
        val fixedOnly = PlatformFee.of(Amount.of(50, eur), 0)
        val percentOnly = PlatformFee.of(Amount.zero(eur), 290)
        val none = PlatformFee.of(Amount.zero(eur), 0)

        assertEquals(Amount.of(200, eur), fixedPlusFivePercent.feeFor(Amount.of(3000, eur))) // 50 + 150
        assertEquals(Amount.of(350, eur), fixedPlusFivePercent.feeFor(Amount.of(6000, eur))) // 50 + 300
        assertEquals(Amount.of(50, eur), fixedOnly.feeFor(Amount.of(6000, eur)))
        assertEquals(Amount.of(28, eur), percentOnly.feeFor(Amount.of(999, eur))) // 28.971 rounds down
        assertEquals(Amount.zero(eur), none.feeFor(Amount.of(3000, eur)))
        assertFailsWith<PaymentDomainException.CurrencyMismatchException> {
            fixedPlusFivePercent.feeFor(Amount.of(3000, Currency("USD")))
        }
    }
}
