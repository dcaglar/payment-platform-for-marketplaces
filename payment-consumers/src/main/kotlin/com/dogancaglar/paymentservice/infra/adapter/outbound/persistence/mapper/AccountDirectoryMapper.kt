package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AccountDirectoryMapper {
    fun findByEntityAndType(
        @Param("accountType") accountType: String,
        @Param("masterAccountCode") masterAccountCode: String,
        @Param("currency") currency: String
    ): AccountProfile?

    fun findBySubEntity(
        @Param("accountType") accountType: String,
        @Param("masterAccountCode") masterAccountCode: String,
        @Param("subEntityId") subEntityId: String,
        @Param("currency") currency: String
    ): AccountProfile?

    fun findAllBySubEntity(
        @Param("accountType") accountType: String,
        @Param("subEntityId") subEntityId: String
    ): List<AccountProfile>

    fun findAllByMaster(
        @Param("accountType") accountType: String,
        @Param("masterAccountCode") masterAccountCode: String
    ): List<AccountProfile>

    fun findByAccountCode(
        @Param("accountCode") accountCode: String
    ): AccountProfile?
}
