package com.dogancaglar.paymentservice.seed

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checked-in Keycloak seed must be exactly what merchants.json generates.
 * With -Dseed.regenerate=true it rewrites the seed file instead of comparing.
 */
class RealmSeedFileTest {

    @Test
    fun `keycloak merchants seed is generated from merchants json`() {
        val generated = RealmSeedGenerator.generate(MerchantSeed.read(MerchantSeed.file.readText()))

        if (System.getProperty("seed.regenerate") == "true") {
            RealmSeedGenerator.seedFile.writeText(generated)
            return
        }

        val json = ObjectMapper()
        assertEquals(
            json.readTree(generated),
            json.readTree(RealmSeedGenerator.seedFile.readText()),
            "keycloak/realm/merchants-seed.json is out of date; regenerate it from merchants.json (see " +
                "RealmSeedGenerator)"
        )
    }
}
