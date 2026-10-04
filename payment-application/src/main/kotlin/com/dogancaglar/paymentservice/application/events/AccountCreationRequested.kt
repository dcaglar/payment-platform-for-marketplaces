package com.dogancaglar.paymentservice.application.events

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.application.command.CreateAccountCommand
import com.dogancaglar.paymentservice.domain.model.account.Address
import com.dogancaglar.paymentservice.domain.model.account.PlatformFee
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import java.time.Instant

/**
 * A merchant account (with its sellers) was requested via POST /api/v1/accounts and is waiting to be created.
 * Carries the whole request as plain values; [toCommand] turns it back into the command for the consumer.
 */
data class AccountCreationRequested(
    val merchantAccountCode: String,
    val legalName: String,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val postalCode: String,
    val country: String,
    val industry: String,
    val currency: String,
    val platformFeeFixed: Long,
    val platformFeeBps: Int,
    val autoCaptured: Boolean,
    val autoSettled: Boolean,
    val sellerAccountCodes: List<String>,
    override val timestamp: Instant = Utc.nowInstant()
) : Event {
    override val eventType: String = EventType.ACCOUNT_CREATION_REQUESTED

    // One creation per merchant code. Redis skips a repeat within the hour; after that the creation
    // itself is idempotent (a merchant that already exists writes nothing).
    override fun deterministicEventId(): String = "$merchantAccountCode:$eventType"

    fun toCommand(): CreateAccountCommand {
        val currencyValue = Currency(currency)
        var fixedFee = Amount.zero(currencyValue)
        if (platformFeeFixed > 0) {
            fixedFee = Amount.of(platformFeeFixed, currencyValue)
        }
        return CreateAccountCommand(
            merchantAccountCode = merchantAccountCode,
            legalName = legalName,
            address = Address.of(addressLine1, addressLine2, city, postalCode, country),
            industry = industry,
            currency = currencyValue,
            platformFee = PlatformFee.of(fixedFee, platformFeeBps),
            isAutoCaptured = autoCaptured,
            isAutoSettled = autoSettled,
            sellerAccountCodes = sellerAccountCodes
        )
    }

    companion object {
        fun from(cmd: CreateAccountCommand, timestamp: Instant = Utc.nowInstant()) = AccountCreationRequested(
            merchantAccountCode = cmd.merchantAccountCode,
            legalName = cmd.legalName,
            addressLine1 = cmd.address.line1,
            addressLine2 = cmd.address.line2,
            city = cmd.address.city,
            postalCode = cmd.address.postalCode,
            country = cmd.address.country,
            industry = cmd.industry,
            currency = cmd.currency.currencyCode,
            platformFeeFixed = cmd.platformFee.fixed.quantity,
            platformFeeBps = cmd.platformFee.basisPoints,
            autoCaptured = cmd.isAutoCaptured,
            autoSettled = cmd.isAutoSettled,
            sellerAccountCodes = cmd.sellerAccountCodes,
            timestamp = timestamp
        )
    }
}
