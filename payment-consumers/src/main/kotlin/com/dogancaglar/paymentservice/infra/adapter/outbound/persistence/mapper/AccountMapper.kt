package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.common.db.entity.LedgerAccountEntity
import com.dogancaglar.common.db.entity.MerchantEntity
import com.dogancaglar.common.db.entity.SellerEntity
import org.apache.ibatis.annotations.Mapper

/** Writes to and reads from `merchants`, `sellers` and `ledger_accounts`. SQL in resources/mapper/AccountMapper.xml. */
@Mapper
interface AccountMapper {

    /** Inserts the merchant unless one with this code already exists. 1 = inserted, 0 = already there. */
    fun insertMerchantIfAbsent(merchant: MerchantEntity): Int

    /** Inserts the seller; fails (duplicate key) if a seller with this code already exists. */
    fun insertSeller(seller: SellerEntity): Int

    /** Inserts the ledger account; fails (duplicate key) if one with this code already exists. */
    fun insertLedgerAccount(ledgerAccount: LedgerAccountEntity): Int

    /** The merchant with this code, or null. */
    fun findMerchantByCode(accountCode: String): MerchantEntity?
}
