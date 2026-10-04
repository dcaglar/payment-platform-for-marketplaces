package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.common.db.entity.AccountEntity
import org.apache.ibatis.annotations.Mapper

/** Writes to and reads from the central `accounts` table. SQL in resources/mapper/AccountMapper.xml. */
@Mapper
interface AccountMapper {

    /** Inserts the row; fails (duplicate key) if an account with this code already exists. */
    fun insert(account: AccountEntity): Int

    /** Inserts the row unless an account with this code already exists. 1 = inserted, 0 = already there. */
    fun insertIfAbsent(account: AccountEntity): Int

    /** The merchant row with this code, or null. */
    fun findMerchantByCode(accountCode: String): AccountEntity?
}
