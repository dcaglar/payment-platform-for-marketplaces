package com.dogancaglar.paymentservice.infra.adapter.outbound.redis.client

import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class AccountProfileRedisCache(
    private val redisTemplate: StringRedisTemplate,
    @Qualifier("myObjectMapper") private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun getProfile(accountType: AccountType, masterAccountCode: String, subEntityId: String?, currency: Currency): AccountProfile? {
        val key = buildKey(accountType, masterAccountCode, subEntityId, currency)
        val json = redisTemplate.opsForValue().get(key)
        return if (json != null) {
            try {
                objectMapper.readValue(json, AccountProfile::class.java)
            } catch (e: Exception) {
                logger.warn("Failed to deserialize AccountProfile from Redis. Key: $key", e)
                null
            }
        } else {
            null
        }
    }

    fun saveProfile(profile: AccountProfile, ttl: Duration = Duration.ofHours(24)) {
        val key = buildKey(profile.type, profile.masterAccountCode, profile.subEntityId, profile.currency)
        try {
            val json = objectMapper.writeValueAsString(profile)
            redisTemplate.opsForValue().set(key, json, ttl)
        } catch (e: Exception) {
            logger.warn("Failed to serialize AccountProfile to Redis. Key: $key", e)
        }
    }

    private fun buildKey(accountType: AccountType, masterAccountCode: String, subEntityId: String?, currency: Currency): String {
        if (subEntityId == null) {
            return "account:profile:${accountType.name}:$masterAccountCode:${currency.currencyCode}"
        }
        return "account:profile:${accountType.name}:$masterAccountCode:$subEntityId:${currency.currencyCode}"
    }
}
