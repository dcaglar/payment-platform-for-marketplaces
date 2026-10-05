package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.exception.LedgerDomainException
import com.dogancaglar.paymentservice.domain.model.account.Account
import com.dogancaglar.paymentservice.domain.model.account.AccountCodes
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
    override val status: AccountStatus
) : Account() {

    override val accountCode: String
        get() = buildCode(type, ownerCode, sellerCode, currency)

    fun isDebitAccount() = type.normalBalance == NormalBalance.DEBIT

    fun isCreditAccount() = type.normalBalance == NormalBalance.CREDIT

    companion object {

        /**
         * A new ledger account. The owner must match the type's owner level: platform types belong to GLOBAL,
         * merchant types to a merchant, seller types to a seller under its merchant.
         */
        fun createNew(
            type: LedgerAccountType,
            ownerCode: String,
            sellerCode: String?,
            currency: Currency,
            status: AccountStatus = AccountStatus.ACTIVE
        ): LedgerAccount {
            when (type.owner) {
                AccountOwner.PLATFORM -> {
                    require(
                        ownerCode == AccountCodes.PLATFORM
                    ) {
                        LedgerDomainException.InvariantViolationException(
                            "$type belongs to the platform, owner must be ${AccountCodes.PLATFORM}, was $ownerCode"
                        )
                    }
                    require(sellerCode == null) {
                        LedgerDomainException.InvariantViolationException(
                            "$type is a platform account and has no seller"
                        )
                    }
                }
                AccountOwner.MERCHANT -> {
                    require(
                        AccountCodes.isValidOwnerCode(ownerCode)
                    ) {
                        LedgerDomainException.InvariantViolationException(
                            "$type needs a merchant code as owner, was '$ownerCode'"
                        )
                    }
                    require(sellerCode == null) {
                        LedgerDomainException.InvariantViolationException(
                            "$type is a merchant account and has no seller"
                        )
                    }
                }
                AccountOwner.SELLER -> {
                    require(
                        AccountCodes.isValidOwnerCode(ownerCode)
                    ) {
                        LedgerDomainException.InvariantViolationException(
                            "$type needs the seller's merchant code as owner, was '$ownerCode'"
                        )
                    }
                    require(
                        sellerCode != null && AccountCodes.isValidOwnerCode(sellerCode)
                    ) {
                        LedgerDomainException.InvariantViolationException(
                            "$type needs a seller code, was '$sellerCode'"
                        )
                    }
                }
            }
            return LedgerAccount(type, ownerCode, sellerCode, currency, status)
        }

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
            require(parts.size == 3 || parts.size == 4) {
                LedgerDomainException.InvariantViolationException("Not a ledger account code: $accountCode")
            }
            require(parts[0] == type.name) {
                LedgerDomainException.InvariantViolationException(
                    "Account code $accountCode does not belong to type $type"
                )
            }
            var sellerCode: String? = null
            if (parts.size == 4) {
                sellerCode = parts[2]
            }
            return LedgerAccount(type, parts[1], sellerCode, Currency(parts[parts.size - 1]), AccountStatus.ACTIVE)
        }

        private fun buildCode(type: LedgerAccountType, ownerCode: String, sellerCode: String?, currency: Currency): String {
            if (sellerCode == null) {
                return "${type.name}.$ownerCode.${currency.currencyCode}"
            }
            return "${type.name}.$ownerCode.$sellerCode.${currency.currencyCode}"
        }
    }
}
