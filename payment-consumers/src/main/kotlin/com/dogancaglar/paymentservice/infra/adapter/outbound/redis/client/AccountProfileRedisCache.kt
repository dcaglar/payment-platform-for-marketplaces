package com.dogancaglar.paymentservice.infra.adapter.outbound.redis.client

import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class AccountProfileRedisCache(
    private val redisTemplate: StringRedisTemplate,
    @Qualifier("myObjectMapper") private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun getProfile(
        accountType: LedgerAccountType,
        masterAccountCode: String,
        subEntityId: String?,
        currency: Currency
    ): AccountProfile? {
        val key = buildKey(accountType, masterAccountCode, subEntityId, currency)
        val json = redisTemplate.opsForValue().get(key)
        return if (json != null) {
            try {
                objectMapper.readValue(json, AccountProfile::class.java)
            } catch (e: JsonProcessingException) {
                // the cache is best effort: an unreadable entry counts as not cached (the database is the source)
                logger.warn("Failed to deserialize AccountProfile from Redis. Key: $key", e)
                null
            }
        } else {
            null
        }
    }

    fun saveProfile(profile: AccountProfile, ttl: Duration = Duration.ofHours(PROFILE_TTL_HOURS)) {
        val key = buildKey(profile.type, profile.masterAccountCode, profile.subEntityId, profile.currency)
        // the cache is best effort: a failure to fill it is logged, never fails the caller
        val json = try {
            objectMapper.writeValueAsString(profile)
        } catch (e: JsonProcessingException) {
            logger.warn("Failed to serialize AccountProfile for Redis. Key: $key", e)
            return
        }
        try {
            redisTemplate.opsForValue().set(key, json, ttl)
        } catch (e: DataAccessException) {
            logger.warn("Failed to write AccountProfile to Redis. Key: $key", e)
        }
    }

    private fun buildKey(
        accountType: LedgerAccountType,
        masterAccountCode: String,
        subEntityId: String?,
        currency: Currency
    ): String {
        if (subEntityId == null) {
            return "account:profile:${accountType.name}:$masterAccountCode:${currency.currencyCode}"
        }
        return "account:profile:${accountType.name}:$masterAccountCode:$subEntityId:${currency.currencyCode}"
    }

    private companion object {
        const val PROFILE_TTL_HOURS = 24L
    }
}
