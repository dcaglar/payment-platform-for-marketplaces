package com.dogancaglar.paymentservice.seed

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File

/**
 * Generates the Keycloak seed `keycloak/realm/merchants-seed.json` from `charts/central-db/seed/merchants.json`
 * (the file that also seeds central-db), as a partial import on top of the realm `ecommerce-platform.json`:
 *   per merchant  client merchant-api-<MERCHANT> (client credentials, claim merchant_id, secret "<clientId>-secret")
 *                 and user <merchant> / merchant123 (role MERCHANT, claim merchant_id)
 *   per seller    user <seller> / seller123 (role SELLER, claim seller_id)
 *                 and its service account (the backend itself) with role MERCHANT
 * The secrets and passwords are local / test seed data only.
 *
 * Regenerate after changing merchants.json:
 *   mvn -pl payment-consumers -am test -Dtest=RealmSeedFileTest -Dseed.regenerate=true -Dsurefire.failIfNoSpecifiedTests=false
 */
object RealmSeedGenerator {

    val seedFile = File("../keycloak/realm/merchants-seed.json")

    private val json = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    fun generate(merchants: List<MerchantSeed>): String {
        val seed = json.createObjectNode()
        seed.put("ifResourceExists", "OVERWRITE")
        val clients = seed.putArray("clients")
        val users = seed.putArray("users")
        for (m in merchants) {
            clients.add(merchantApiClient("merchant-api-${m.merchantAccount}", m.merchantAccount))
            users.add(serviceAccount("merchant-api-${m.merchantAccount}"))
            users.add(
                person(m.merchantAccount.lowercase(), "merchant123", "MERCHANT", "merchant_id", m.merchantAccount)
            )
            for (seller in m.sellers) {
                users.add(person(seller.lowercase(), "seller123", "SELLER", "seller_id", seller))
            }
        }
        return json.writeValueAsString(seed) + "\n"
    }

    private fun merchantApiClient(clientId: String, merchant: String): ObjectNode {
        val client = json.createObjectNode()
        client.put("clientId", clientId)
        client.put("name", "$merchant backend (client credentials)")
        client.put("enabled", true)
        client.put("protocol", "openid-connect")
        client.put("publicClient", false)
        client.put("clientAuthenticatorType", "client-secret")
        client.put("secret", "$clientId-secret")
        client.put("standardFlowEnabled", false)
        client.put("implicitFlowEnabled", false)
        client.put("directAccessGrantsEnabled", false)
        client.put("serviceAccountsEnabled", true)
        client.put("fullScopeAllowed", true)
        client.putObject("attributes").put("access.token.lifespan", "36000")
        val mapper = client.putArray("protocolMappers").addObject()
        mapper.put("name", "merchant_id")
        mapper.put("protocol", "openid-connect")
        mapper.put("protocolMapper", "oidc-hardcoded-claim-mapper")
        mapper.put("consentRequired", false)
        val config = mapper.putObject("config")
        config.put("claim.name", "merchant_id")
        config.put("claim.value", merchant)
        config.put("jsonType.label", "String")
        config.put("access.token.claim", "true")
        config.put("id.token.claim", "true")
        config.put("userinfo.token.claim", "true")
        return client
    }

    // the merchant backend's role: Keycloak keeps a client's roles on its service-account user
    private fun serviceAccount(clientId: String): ObjectNode {
        val user = json.createObjectNode()
        user.put("username", "service-account-${clientId.lowercase()}")
        user.put("enabled", true)
        user.put("serviceAccountClientId", clientId)
        user.putArray("realmRoles").add("MERCHANT")
        return user
    }

    private fun person(username: String, password: String, role: String, claim: String, value: String): ObjectNode {
        val user = json.createObjectNode()
        user.put("username", username)
        user.put("enabled", true)
        user.put("emailVerified", true)
        val credential = user.putArray("credentials").addObject()
        credential.put("type", "password")
        credential.put("value", password)
        credential.put("temporary", false)
        user.putArray("realmRoles").add(role)
        user.putObject("attributes").putArray(claim).add(value)
        return user
    }
}
