package com.dogancaglar.paymentservice.domain.model.common

import com.dogancaglar.paymentservice.domain.exception.PaymentDomainException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AmountTest {

    @Test
    fun `should create Amount with value and currency`() {
        val amount = Amount.of(100000L, Currency("USD")) // $1000.00 = 100000 cents

        assertEquals(100000L, amount.quantity)
        assertEquals("USD", amount.currency.currencyCode)
    }

    @Test
    fun `sanity check on Amount negate`() {
        val amt = Amount.of(1000, Currency("EUR"))
        val neg = amt.negate()
        println("Original: ${amt.quantity}, Negated: ${neg.quantity}")
    }

    @Test
    fun `should reject Amount with zero value`() {
        val exception = assertThrows(PaymentDomainException.InvalidAmountException::class.java) {
            Amount.of(0L, Currency("USD"))
        }
        assertTrue(exception.message?.contains("must be greater than zero") == true)
    }

    @Test
    fun `should create Amount with decimal equivalent value`() {
        val amount = Amount.of(9999L, Currency("EUR")) // €99.99 = 9999 cents

        assertEquals(9999L, amount.quantity)
        assertEquals("EUR", amount.currency.currencyCode)
    }

    @Test
    fun `should support different currencies`() {
        val usd = Amount.of(10000L, Currency("USD")) // $100.00
        val eur = Amount.of(10000L, Currency("EUR")) // €100.00
        val gbp = Amount.of(10000L, Currency("GBP")) // £100.00
        val jpy = Amount.of(10000L, Currency("JPY")) // ¥100.00

        assertEquals("USD", usd.currency.currencyCode)
        assertEquals("EUR", eur.currency.currencyCode)
        assertEquals("GBP", gbp.currency.currencyCode)
        assertEquals("JPY", jpy.currency.currencyCode)
    }

    @Test
    fun `equals should return true for same values`() {
        val amount1 = Amount.of(100000L, Currency("USD"))
        val amount2 = Amount.of(100000L, Currency("USD"))

        assertEquals(amount1, amount2)
    }

    @Test
    fun `equals should return false for different values`() {
        val amount1 = Amount.of(100000L, Currency("USD"))
        val amount2 = Amount.of(200000L, Currency("USD"))

        assertNotEquals(amount1, amount2)
    }

    @Test
    fun `equals should return false for different currencies`() {
        val amount1 = Amount.of(100000L, Currency("USD"))
        val amount2 = Amount.of(100000L, Currency("EUR"))
        assertNotEquals(amount1, amount2)
    }

    @Test
    fun `hashCode should be equal for equal amounts`() {
        val amount1 = Amount.of(100000L, Currency("USD"))
        val amount2 = Amount.of(100000L, Currency("USD"))

        assertEquals(amount1.hashCode(), amount2.hashCode())
    }

    @Test
    fun `copy should create new instance with modified value`() {
        val original = Amount.of(100000L, Currency("USD"))
        val copied = original.copy(quantity = 200000L)

        assertEquals(100000L, original.quantity)
        assertEquals(200000L, copied.quantity)
        assertEquals("USD", copied.currency.currencyCode)
    }

    @Test
    fun `copy should create new instance with modified currency`() {
        val original = Amount.of(100000L, Currency("USD"))
        val copied = original.copy(currency = Currency("EUR"))

        assertEquals("USD", original.currency.currencyCode)
        assertEquals("EUR", copied.currency.currencyCode)
        assertEquals(100000L, copied.quantity)
    }

    @Test
    fun `should handle large values`() {
        val largeValue = 999999999999L // $9,999,999,999.99
        val amount = Amount.of(largeValue, Currency("USD"))

        assertEquals(largeValue, amount.quantity)
    }

    @Test
    fun `should reject negative values`() {
        val exception = assertThrows(PaymentDomainException.InvalidAmountException::class.java) {
            Amount.of(-10000L, Currency("USD"))
        }
        assertTrue(exception.message?.contains("must be greater than zero") == true)
    }

    @Test
    fun `should preserve Long precision`() {
        val preciseValue = 123456789L // $1,234,567.89
        val amount = Amount.of(preciseValue, Currency("USD"))

        assertEquals(preciseValue, amount.quantity)
    }

    @Test
    fun `should handle cent values correctly`() {
        val oneCent = Amount.of(1L, Currency("USD")) // $0.01
        val ninetNineCents = Amount.of(99L, Currency("USD")) // $0.99
        val oneDollar = Amount.of(100L, Currency("USD")) // $1.00

        assertEquals(1L, oneCent.quantity)
        assertEquals(99L, ninetNineCents.quantity)
        assertEquals(100L, oneDollar.quantity)
    }

    @Test
    fun `toString should include value and currency`() {
        val amount = Amount.of(100000L, Currency("USD"))
        val string = amount.toString()

        assertTrue(string.contains("100000"))
        assertTrue(string.contains("USD"))
    }

    @Test
    fun `should be usable in collections`() {
        val amounts = listOf(
            Amount.of(10000L, Currency("USD")), // $100.00
            Amount.of(20000L, Currency("EUR")), // €200.00
            Amount.of(30000L, Currency("GBP")) // £300.00
        )

        assertEquals(3, amounts.size)
        assertTrue(amounts.contains(Amount.of(10000L, Currency("USD"))))
    }

    @Test
    fun `should be usable as map key`() {
        val amountMap = mapOf(
            Amount.of(10000L, Currency("USD")) to "One hundred dollars",
            Amount.of(20000L, Currency("EUR")) to "Two hundred euros"
        )

        assertEquals("One hundred dollars", amountMap[Amount.of(10000L, Currency("USD"))])
        assertEquals("Two hundred euros", amountMap[Amount.of(20000L, Currency("EUR"))])
    }

    @Test
    fun `should handle very small values`() {
        val smallValue = 1L // $0.01
        val amount = Amount.of(smallValue, Currency("USD"))

        assertEquals(smallValue, amount.quantity)
    }

    @Test
    fun `should create independent instances`() {
        val value = 100000L
        val amount1 = Amount.of(value, Currency("USD"))
        val amount2 = Amount.of(value, Currency("USD"))

        assertEquals(amount1, amount2)
        assertTrue(amount1 !== amount2) // Different objects
    }

    @Test
    fun `should handle common dollar amounts correctly`() {
        val cases = mapOf(
            99L to "99 cents",
            100L to "1 dollar",
            2000L to "20 dollars",
            150L to "1.50 dollars",
            999999L to "9999.99 dollars"
        )

        cases.forEach { (cents, description) ->
            val amount = Amount.of(cents, Currency("USD"))
            assertEquals(cents, amount.quantity, "Failed for $description")
        }
    }
}
