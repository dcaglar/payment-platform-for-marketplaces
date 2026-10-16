package com.dogancaglar.common.db.converter

import com.dogancaglar.common.db.entity.LedgerAccountEntity
import com.dogancaglar.common.db.entity.MerchantEntity
import com.dogancaglar.common.db.entity.SellerEntity
import com.dogancaglar.paymentservice.domain.model.account.AccountStatus
import com.dogancaglar.paymentservice.domain.model.account.Address
import com.dogancaglar.paymentservice.domain.model.account.MerchantAccount
import com.dogancaglar.paymentservice.domain.model.account.PlatformFee
import com.dogancaglar.paymentservice.domain.model.account.SellerAccount
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount

/** Domain accounts <-> rows of `merchants`, `sellers` and `ledger_accounts`. */
object AccountEntityMapper {

    /** [profileJson] is the merchant's legal name, address and industry, already serialized by the adapter. */
    fun toEntity(merchant: MerchantAccount, profileJson: String): MerchantEntity =
        MerchantEntity(
            accountCode = merchant.accountCode,
            status = merchant.status.name,
            currency = merchant.currency.currencyCode,
            isAutoCaptured = merchant.isAutoCaptured,
            isAutoSettled = merchant.isAutoSettled,
            platformFeeFixed = merchant.platformFee.fixed.quantity,
            platformFeeBps = merchant.platformFee.basisPoints,
            profile = profileJson
        )

    /**
     * A merchant row back to the domain. [legalName], [address] and [industry] come from its profile JSON, read by the
     * adapter.
     */
    fun toMerchant(entity: MerchantEntity, legalName: String, address: Address, industry: String): MerchantAccount {
        val currency = Currency(entity.currency)
        return MerchantAccount.rehydrate(
            accountCode = entity.accountCode,
            status = AccountStatus.valueOf(entity.status),
            legalName = legalName,
            address = address,
            industry = industry,
            currency = currency,
            isAutoCaptured = entity.isAutoCaptured,
            isAutoSettled = entity.isAutoSettled,
            platformFee = PlatformFee.of(Amount.of(entity.platformFeeFixed, currency), entity.platformFeeBps)
        )
    }

    fun toEntity(seller: SellerAccount): SellerEntity =
        SellerEntity(seller.accountCode, seller.masterAccountCode, seller.status.name)

    fun toEntity(ledgerAccount: LedgerAccount): LedgerAccountEntity =
        LedgerAccountEntity(
            accountCode = ledgerAccount.accountCode,
            ledgerType = ledgerAccount.type.name,
            ownerCode = ledgerAccount.ownerCode,
            sellerCode = ledgerAccount.sellerCode,
            currency = ledgerAccount.currency.currencyCode,
            status = ledgerAccount.status.name
        )
}
