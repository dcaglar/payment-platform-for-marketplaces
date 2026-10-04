package com.dogancaglar.paymentservice.seed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checked-in account seed must be exactly what merchants.json generates.
 * With -Dseed.regenerate=true it rewrites the seed file instead of comparing.
 */
class AccountSeedFileTest {

    @Test
    fun `accounts seed is generated from merchants json`() {
        val generated = AccountSeedGenerator.generate(MerchantSeed.read(MerchantSeed.file.readText()))

        if (System.getProperty("seed.regenerate") == "true") {
            AccountSeedGenerator.seedFile.writeText(generated)
            return
        }

        assertEquals(
            generated,
            AccountSeedGenerator.seedFile.readText(),
            "accounts-seed.sql is out of date; regenerate it from merchants.json (see AccountSeedGenerator)"
        )
    }
}
