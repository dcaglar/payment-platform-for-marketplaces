package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.AccountDirectoryMapper
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import com.dogancaglar.paymentservice.ports.outbound.AccountDirectoryPort
import com.dogancaglar.paymentservice.infra.adapter.outbound.redis.client.AccountProfileRedisCache
import org.springframework.stereotype.Service

@Service
class AccountDirectoryImpl(
    private val mapper: AccountDirectoryMapper,
    private val redisCache: AccountProfileRedisCache
) : AccountDirectoryPort {

    override fun getAccountProfile(accountType: AccountType, masterAccountCode: String, currency: Currency): AccountProfile {
        // 1. Check Cache
        val cached = redisCache.getProfile(accountType, masterAccountCode, null, currency)
        if (cached != null) return cached

        // 2. Fetch from DB
        val dbProfile = mapper.findByEntityAndType(accountType.name, masterAccountCode, currency.currencyCode)
            ?: throw IllegalArgumentException("Account not found: ${accountType.name}.$masterAccountCode.${currency.currencyCode}")

        // 3. Populate Cache
        redisCache.saveProfile(dbProfile)

        return dbProfile
    }

    override fun getSubEntityAccountProfile(accountType: AccountType, masterAccountCode: String, subEntityId: String, currency: Currency): AccountProfile {
        // 1. Check Cache
        val cached = redisCache.getProfile(accountType, masterAccountCode, subEntityId, currency)
        if (cached != null) return cached

        // 2. Fetch from DB
        val dbProfile = mapper.findBySubEntity(accountType.name, masterAccountCode, subEntityId, currency.currencyCode)
            ?: throw IllegalArgumentException("Account not found: ${accountType.name}.$masterAccountCode.$subEntityId.${currency.currencyCode}")

        // 3. Populate Cache
        redisCache.saveProfile(dbProfile)

        return dbProfile
    }

    override fun getAccountProfilesBySubEntity(accountType: AccountType, subEntityId: String): List<AccountProfile> {
        // Not using redis cache for sub entity lookups currently, could be added later
        return mapper.findAllBySubEntity(accountType.name, subEntityId)
    }

    override fun getAccountProfilesByMaster(accountType: AccountType, masterAccountCode: String): List<AccountProfile> {
        // Not using redis cache for merchant lookups currently, could be added later
        return mapper.findAllByMaster(accountType.name, masterAccountCode)
    }

    override fun getAccountByCode(accountCode: String): AccountProfile {
        // Not using redis cache for code lookups currently, could be added later
        return mapper.findByAccountCode(accountCode)
            ?: throw IllegalArgumentException("Account not found: $accountCode")
    }
}
