package com.dogancaglar.paymentservice.domain.model.account

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountOwner
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType

/**
 * The merchant a payment's `merchantAccount` refers to. Holds who the merchant is and how its payments are
 * processed; it has no ledger meaning itself. Its ledger accounts are generated from it ([ledgerAccounts]).
 */
data class MerchantAccount private constructor(
    override val accountCode: String,
    override val status: AccountStatus,
    val legalName: String,
    val address: Address,
    val industry: String,
    val currency: Currency,
    val isAutoCaptured: Boolean, // capture is requested automatically right after a successful authorization
    val isAutoSettled: Boolean, // capture confirmation and settlement are simulated (no acquirer)
    val platformFee: PlatformFee // what we charge the merchant per payment, taken from its payable
) : Account() {

    /** One ledger account per merchant-level type, in the merchant's currency. */
    fun ledgerAccounts(): List<LedgerAccount> = ledgerAccounts(currency)

    /** One ledger account per merchant-level type, in the given currency (for when a merchant gets more currencies). */
    fun ledgerAccounts(currency: Currency): List<LedgerAccount> {
        val accounts = mutableListOf<LedgerAccount>()
        for (type in LedgerAccountType.entries) {
            if (type.owner == AccountOwner.MERCHANT) {
                accounts.add(LedgerAccount.createNew(type, accountCode, null, currency))
            }
        }
        return accounts
    }

    companion object {
        fun createNew(
            accountCode: String,
            legalName: String,
            address: Address,
            industry: String,
            currency: Currency,
            platformFee: PlatformFee,
            isAutoCaptured: Boolean = true, // default: capture right after a successful authorization
            isAutoSettled: Boolean = false,
            status: AccountStatus = AccountStatus.ACTIVE
        ): MerchantAccount {
            require(AccountCodes.isValidOwnerCode(accountCode)) { "Invalid merchant account code: '$accountCode'" }
            require(legalName.isNotBlank()) { "Merchant legal name must not be blank" }
            require(industry.isNotBlank()) { "Merchant industry must not be blank" }
            require(platformFee.fixed.currency == currency) {
                "Platform fee currency ${platformFee.fixed.currency.currencyCode} must be the merchant currency ${currency.currencyCode}"
            }
            return MerchantAccount(accountCode, status, legalName, address, industry, currency, isAutoCaptured, isAutoSettled, platformFee)
        }

        /** Rebuilds from persisted state. Trusts the stored data. */
        fun rehydrate(
            accountCode: String,
            status: AccountStatus,
            legalName: String,
            address: Address,
            industry: String,
            currency: Currency,
            isAutoCaptured: Boolean,
            isAutoSettled: Boolean,
            platformFee: PlatformFee
        ): MerchantAccount =
            MerchantAccount(accountCode, status, legalName, address, industry, currency, isAutoCaptured, isAutoSettled, platformFee)
    }
}

data class Address private constructor(
    val line1: String,
    val line2: String?,
    val city: String,
    val postalCode: String,
    val country: String // ISO 3166-1 alpha-2
) {
    companion object {
        fun of(line1: String, line2: String?, city: String, postalCode: String, country: String): Address {
            require(line1.isNotBlank()) { "Address line1 must not be blank" }
            require(city.isNotBlank()) { "Address city must not be blank" }
            require(postalCode.isNotBlank()) { "Address postal code must not be blank" }
            require(
                country.matches(Regex("^[A-Z]{2}$"))
            ) { "Address country must be an ISO 3166-1 alpha-2 code, was '$country'" }
            return Address(line1, line2, city, postalCode, country)
        }
    }
}

/** The platform fee per payment: a fixed part plus a percentage (in basis points: 150 = 1.5%). Either part may be 0. */
data class PlatformFee private constructor(
    val fixed: Amount,
    val basisPoints: Int
) {

    /**
     * The fee for one payment of [amount]: the fixed part plus [basisPoints] / 10,000 of the amount, the percentage
     * part rounded down to the cent. E.g. €0.50 + 5% (500) on €30.00: 50 + 3000 × 500 / 10,000 = 50 + 150 = 200 cents.
     */
    fun feeFor(amount: Amount): Amount {
        require(amount.currency == fixed.currency) {
            "Payment currency ${amount.currency.currencyCode} differs from the platform fee currency ${fixed.currency.currencyCode}"
        }
        val cents = fixed.quantity + amount.quantity * basisPoints / 10_000
        if (cents == 0L) {
            return Amount.zero(amount.currency)
        }
        return Amount.of(cents, amount.currency)
    }

    companion object {
        fun of(fixed: Amount, basisPoints: Int): PlatformFee {
            require(
                basisPoints in 0..10_000
            ) { "Platform fee basis points must be between 0 and 10000, was $basisPoints" }
            return PlatformFee(fixed, basisPoints)
        }
    }
}
