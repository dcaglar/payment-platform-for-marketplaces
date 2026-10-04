package com.dogancaglar.paymentservice.domain.model.account

/**
 * What every kind of account has: a code and a status.
 *
 * Kinds: [PlatformAccount] (us), [MerchantAccount] (what a payment's merchantAccount refers to),
 * [SellerAccount] (a seller under a merchant), and ledger accounts
 * ([com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount]), which are generated for those owners.
 */
abstract class Account {
    abstract val accountCode: String
    abstract val status: AccountStatus
}

enum class AccountStatus { ACTIVE, SUSPENDED, CLOSED }

object AccountCodes {
    /** The platform's own code; platform ledger accounts belong to it. */
    const val PLATFORM = "GLOBAL"

    // Letters, digits, '-' and '_'. No '.', because ledger account codes use '.' as separator.
    private val OWNER_CODE = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

    /** A merchant or seller code: valid characters and not the platform's own code. */
    fun isValidOwnerCode(code: String): Boolean = OWNER_CODE.matches(code) && code != PLATFORM
}
