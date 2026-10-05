package com.dogancaglar.common.db.converter

import com.dogancaglar.common.db.entity.AccountEntity
import com.dogancaglar.paymentservice.domain.model.account.AccountStatus
import com.dogancaglar.paymentservice.domain.model.account.Address
import com.dogancaglar.paymentservice.domain.model.account.MerchantAccount
import com.dogancaglar.paymentservice.domain.model.account.PlatformAccount
import com.dogancaglar.paymentservice.domain.model.account.PlatformFee
import com.dogancaglar.paymentservice.domain.model.account.SellerAccount
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount

/** Domain accounts <-> `accounts` rows. */
object AccountEntityMapper {

    fun toEntity(platform: PlatformAccount): AccountEntity =
        AccountEntity(
            platform.accountCode,
            "PLATFORM",
            platform.status.name,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        )

    /** [profileJson] is the merchant's legal name, address and industry, already serialized by the adapter. */
    fun toEntity(merchant: MerchantAccount, profileJson: String): AccountEntity =
        AccountEntity(
            accountCode = merchant.accountCode,
            kind = "MERCHANT",
            status = merchant.status.name,
            parentCode = null,
            ledgerType = null,
            currency = merchant.currency.currencyCode,
            isAutoCaptured = merchant.isAutoCaptured,
            isAutoSettled = merchant.isAutoSettled,
            platformFeeFixed = merchant.platformFee.fixed.quantity,
            platformFeeBps = merchant.platformFee.basisPoints,
            profile = profileJson
        )

    /**
     * A MERCHANT row back to the domain. [legalName], [address] and [industry] come from its profile JSON, read by the
     * adapter.
     */
    fun toMerchant(entity: AccountEntity, legalName: String, address: Address, industry: String): MerchantAccount {
        val currency = Currency(entity.currency!!)
        return MerchantAccount.rehydrate(
            accountCode = entity.accountCode,
            status = AccountStatus.valueOf(entity.status),
            legalName = legalName,
            address = address,
            industry = industry,
            currency = currency,
            isAutoCaptured = entity.isAutoCaptured!!,
            isAutoSettled = entity.isAutoSettled!!,
            platformFee = PlatformFee.of(Amount.of(entity.platformFeeFixed!!, currency), entity.platformFeeBps!!)
        )
    }

    fun toEntity(seller: SellerAccount): AccountEntity =
        AccountEntity(
            seller.accountCode,
            "SELLER",
            seller.status.name,
            seller.masterAccountCode,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        )

    /** A ledger account hangs under its owner row: the seller for seller accounts, otherwise the merchant or GLOBAL. */
    fun toEntity(ledgerAccount: LedgerAccount): AccountEntity {
        var parentCode = ledgerAccount.ownerCode
        if (ledgerAccount.sellerCode != null) {
            parentCode = ledgerAccount.sellerCode!!
        }
        return AccountEntity(
            accountCode = ledgerAccount.accountCode,
            kind = "LEDGER",
            status = ledgerAccount.status.name,
            parentCode = parentCode,
            ledgerType = ledgerAccount.type.name,
            currency = ledgerAccount.currency.currencyCode,
            isAutoCaptured = null,
            isAutoSettled = null,
            platformFeeFixed = null,
            platformFeeBps = null,
            profile = null
        )
    }
}
