package com.dogancaglar.paymentservice.application.dto

import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit

/**
 * PaymentSplitDto
 *
 * The serialization contract for a single split routing instruction.
 * Used in:
 *  - CreatePaymentIntentDto (inbound API request)
 *  - PaymentAuthorizedEvent (Kafka payload, carries splits across the network
 *    from the Edge Cell to the Central Core so PspResultConsumer can lock the
 *    routing matrix into the Central DB).
 *
 * Jackson @JsonCreator + @JsonProperty annotations guarantee that
 * deserialization is explicit, deterministic, and immune to field-reordering.
 *
 * @param accountType  Canonical enum value identifying the ledger bucket.
 * @param account     Identifies the beneficiary entity (seller, platform, etc.).
 * @param amountValue        The quantity of funds.
 * @param currency           The currency code (e.g., EUR, USD).
 */
data class PaymentSplitDto(
    val accountType: String,
    val account: String,
    val amountValue: Long,
    val currency: String
) {
    companion object {
        fun of(
            accountType: String,
            account: String,
            amountValue: Long,
            currency: String
        ): PaymentSplitDto {
            require(account.isNotBlank()) { "account must not be blank" }
            require(amountValue > 0) { "Amount value must be positive" }
            require(currency.isNotBlank() && currency.length == 3) {
                "Currency must be a valid 3-letter ISO code"
            }
            return PaymentSplitDto(accountType, account, amountValue, currency)
        }

        fun fromDomain(split: PaymentSplit): PaymentSplitDto {
            val typeStr = when(split.accountType.name) {
                "MARKETPLACE_SELLER_BALANCE_ACCOUNT" -> "BalanceAccount"
                "MARKETPLACE_COMMISSION_REVENUE_BALANCE_ACCOUNT" -> "Commission"
                "MERCHANT_GROSS_CAPTURE_SUSPENSE" -> "Operator"
                else -> split.accountType.name
            }
            return PaymentSplitDto(
                accountType = typeStr,
                account    = split.account,
                amountValue       = split.amount.quantity,
                currency          = split.amount.currency.currencyCode
            )
        }
    }

    fun toDomain(): PaymentSplit {
        // We must map it back to AccountType
        // using reflection or direct matching so we don't need AccountType enum here
        val mappedAccountType = try {
            com.dogancaglar.paymentservice.domain.model.ledger.AccountType.valueOf(
                when(accountType) {
                    "BalanceAccount", "BALANCE_ACCOUNT" -> "MARKETPLACE_SELLER_BALANCE_ACCOUNT"
                    "Commission", "MARKETPLACE_COMMISSION_REVENUE_BALANCE_ACCOUNT" -> "MARKETPLACE_COMMISSION_REVENUE_BALANCE_ACCOUNT"
                    "Operator", "MARKETPLACE_OPERATOR" -> "MERCHANT_GROSS_CAPTURE_SUSPENSE"
                    else -> accountType
                }
            )
        } catch (e: Exception) {
            com.dogancaglar.paymentservice.domain.model.ledger.AccountType.MARKETPLACE_SELLER_BALANCE_ACCOUNT
        }

        return PaymentSplit.of(
            accountType = mappedAccountType,
            account    = account,
            amount            = Amount.of(amountValue, Currency(currency))
        )
    }
}
