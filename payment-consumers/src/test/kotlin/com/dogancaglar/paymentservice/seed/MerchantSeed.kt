package com.dogancaglar.paymentservice.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * The seed merchants, read from `charts/central-db/seed/merchants.json` — the only hand-edited seed file.
 * Two seeds are generated from it: the central-db accounts (AccountSeedGenerator) and the Keycloak
 * merchants and sellers (RealmSeedGenerator).
 */
data class MerchantSeed(
    val merchantAccount: String,
    val legalName: String,
    val line1: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val industry: String,
    val currency: String,
    val autoCaptured: Boolean,
    val autoSettled: Boolean,
    val platformFeeFixed: Long,
    val platformFeeBps: Int,
    val sellers: List<String>
) {
    companion object {
        /** Paths are relative to the payment-consumers module (the test's working directory). */
        val file = File("../charts/central-db/seed/merchants.json")

        fun read(json: String): List<MerchantSeed> {
            val merchants = mutableListOf<MerchantSeed>()
            for (m in ObjectMapper().readTree(json)) {
                val sellers = mutableListOf<String>()
                for (seller in m.path("sellers")) {
                    sellers.add(seller.asText())
                }
                merchants.add(
                    MerchantSeed(
                        merchantAccount = m.path("merchantAccount").asText(),
                        legalName = m.path("legalName").asText(),
                        line1 = m.path("address").path("line1").asText(),
                        city = m.path("address").path("city").asText(),
                        postalCode = m.path("address").path("postalCode").asText(),
                        country = m.path("address").path("country").asText(),
                        industry = m.path("industry").asText(),
                        currency = m.path("currency").asText(),
                        autoCaptured = m.path("autoCaptured").asBoolean(),
                        autoSettled = m.path("autoSettled").asBoolean(),
                        platformFeeFixed = m.path("platformFee").path("fixed").asLong(),
                        platformFeeBps = m.path("platformFee").path("bps").asInt(),
                        sellers = sellers
                    )
                )
            }
            return merchants
        }
    }
}
