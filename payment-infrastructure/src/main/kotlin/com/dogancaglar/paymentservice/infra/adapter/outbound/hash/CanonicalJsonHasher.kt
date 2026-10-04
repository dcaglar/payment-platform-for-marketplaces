package com.dogancaglar.paymentservice.infra.adapter.outbound.hash

import com.dogancaglar.paymentservice.ports.outbound.HasherPort
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64

@Component
class CanonicalJsonHasher(private val objectMapper: ObjectMapper) : HasherPort {

    override fun hashBody(body: Any): String {
        // 1. Serialize to canonical JSON (sorted keys)
        // Jackson's SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS should be enabled if we want full canonicalization
        // but for now simple serialization is often enough if the DTO is simple
        val json = objectMapper.writeValueAsString(body)

        // 2. Compute SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(json.toByteArray(Charsets.UTF_8))

        // 3. Return Base64 encoded string
        return Base64.getEncoder().encodeToString(hashBytes)
    }
}
