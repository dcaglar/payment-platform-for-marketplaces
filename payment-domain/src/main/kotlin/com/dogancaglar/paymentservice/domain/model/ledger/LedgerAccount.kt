package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.exception.LedgerDomainException
import com.dogancaglar.paymentservice.domain.model.account.AccountStatus
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.common.require

/**
 * A ledger account: the only kind of account postings can be made to.
 *
 * Its type fixes the normal balance, category and owner level. The account code is derived from its parts,
 * `TYPE.OWNER[.SELLER].CURRENCY`, so it can never disagree with them:
 *   - platform: `PSP_RECEIVABLE.GLOBAL.EUR`
 *   - merchant: `CAPTURE_SUSPENSE.MARKETPLACE-5.EUR`
 *   - seller:   `SELLER_PAYABLE.MARKETPLACE-5.SELLER-5-1.EUR`
 */
data class LedgerAccount private constructor(
    val type: LedgerAccountType,
    val ownerCode: String,
    val sellerCode: String?,
    val currency: Currency,
    val status: AccountStatus
) {

    val accountCode: String
        get() = buildCode(type, ownerCode, sellerCode, currency)

    fun isDebitAccount() = type.normalBalance == NormalBalance.DEBIT

    fun isCreditAccount() = type.normalBalance == NormalBalance.CREDIT

    companion object {
        private const val CODE_PARTS_WITHOUT_SELLER = 3 // TYPE.OWNER.CURRENCY
        private const val CODE_PARTS_WITH_SELLER = 4 // TYPE.OWNER.SELLER.CURRENCY

        /** A new ledger account of [type] for [ownerCode] (a merchant, or GLOBAL), under [sellerCode] if any. */
        fun createNew(
            type: LedgerAccountType,
            ownerCode: String,
            sellerCode: String?,
            currency: Currency,
            status: AccountStatus = AccountStatus.ACTIVE
        ): LedgerAccount = LedgerAccount(type, ownerCode, sellerCode, currency, status)

        /** Rebuilds an account from an account-directory row. Trusts the stored data. */
        fun fromProfile(profile: AccountProfile): LedgerAccount =
            LedgerAccount(
                type = profile.type,
                ownerCode = profile.masterAccountCode,
                sellerCode = profile.subEntityId,
                currency = profile.currency,
                status = profile.status
            )

        /**
         * Rebuilds an account from the code carried in an event (`TYPE.OWNER[.SELLER].CURRENCY`).
         * Trusts the producer; the code's first part must still name the given type.
         */
        fun fromCode(type: LedgerAccountType, accountCode: String): LedgerAccount {
            val parts = accountCode.split(".")
            require(parts.size == CODE_PARTS_WITHOUT_SELLER || parts.size == CODE_PARTS_WITH_SELLER) {
                LedgerDomainException.InvariantViolationException("Not a ledger account code: $accountCode")
            }
            require(parts[0] == type.name) {
                LedgerDomainException.InvariantViolationException(
                    "Account code $accountCode does not belong to type $type"
                )
            }
            var sellerCode: String? = null
            if (parts.size == CODE_PARTS_WITH_SELLER) {
                sellerCode = parts[2]
            }
            return LedgerAccount(type, parts[1], sellerCode, Currency(parts[parts.size - 1]), AccountStatus.ACTIVE)
        }

        private fun buildCode(
            type: LedgerAccountType,
            ownerCode: String,
            sellerCode: String?,
            currency: Currency
        ): String {
            if (sellerCode == null) {
                return "${type.name}.$ownerCode.${currency.currencyCode}"
            }
            return "${type.name}.$ownerCode.$sellerCode.${currency.currencyCode}"
        }
    }
}
