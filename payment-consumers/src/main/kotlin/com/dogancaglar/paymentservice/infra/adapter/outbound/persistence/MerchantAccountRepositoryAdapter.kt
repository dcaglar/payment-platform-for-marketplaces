package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.common.db.converter.AccountEntityMapper
import com.dogancaglar.paymentservice.domain.model.account.Address
import com.dogancaglar.paymentservice.domain.model.account.MerchantAccount
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.AccountMapper
import com.dogancaglar.paymentservice.ports.outbound.MerchantAccountRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Repository

/** Reads merchants from central-db's `accounts` table: one query per call, no cache. */
@Repository
class MerchantAccountRepositoryAdapter(
    private val accountMapper: AccountMapper,
    @Qualifier("myObjectMapper") private val objectMapper: ObjectMapper
) : MerchantAccountRepository {

    override fun findByCode(merchantAccount: String): MerchantAccount? {
        val entity = accountMapper.findMerchantByCode(merchantAccount)
        if (entity == null) {
            return null
        }
        // the profile as AccountCreationTransactionalFacadeAdapter writes it: legalName, address, industry
        val profile = objectMapper.readTree(entity.profile)
        val addressNode = profile.get("address")
        val address = Address.of(
            line1 = addressNode.get("line1").asText(),
            line2 = textOrNull(addressNode.get("line2")),
            city = addressNode.get("city").asText(),
            postalCode = addressNode.get("postalCode").asText(),
            country = addressNode.get("country").asText()
        )
        return AccountEntityMapper.toMerchant(
            entity,
            profile.get("legalName").asText(),
            address,
            profile.get("industry").asText()
        )
    }

    private fun textOrNull(node: JsonNode?): String? {
        if (node == null || node.isNull) {
            return null
        }
        return node.asText()
    }
}
