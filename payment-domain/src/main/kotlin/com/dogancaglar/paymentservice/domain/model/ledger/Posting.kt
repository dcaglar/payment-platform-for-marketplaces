package com.dogancaglar.paymentservice.domain.model.ledger

import com.dogancaglar.paymentservice.domain.model.common.Amount
sealed class Posting(open val account: LedgerAccount, open val amount: Amount) {

    abstract fun getSignedAmount(): Amount
    class Debit private constructor(override val account: LedgerAccount, override val amount: Amount) : Posting(
        account,
        amount
    ) {
        override fun getSignedAmount(): Amount {
            if (
                account.isDebitAccount()
            ) {
                return amount
            } else {
                return amount.negate()
            }
        }

        override fun toString(): String {
            return "Debit(account=$account, amount=$amount)"
        }

        companion object {
            fun create(account: LedgerAccount, amount: Amount): Debit {
                return Debit(account, amount)
            }
        }
    }
    class Credit private constructor(override val account: LedgerAccount, override val amount: Amount) : Posting(
        account,
        amount
    ) {
        override fun getSignedAmount(): Amount {
            if (account.isCreditAccount()) {
                return amount
            } else {
                return amount.negate()
            }
        }

        override fun toString(): String {
            return "Credit(account=$account, amount=$amount)"
        }

        companion object {
            fun create(account: LedgerAccount, amount: Amount): Credit {
                return Credit(account, amount)
            }
        }
    }
}
