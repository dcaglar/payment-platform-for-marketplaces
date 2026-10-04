package com.dogancaglar.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.DirectoryResourceAccessor
import java.net.HttpURLConnection.HTTP_CREATED
import java.net.HttpURLConnection.HTTP_OK
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

/**
 * Infrastructure-agnostic helpers for the E2E harness:
 *   - locating the repo root (to feed Docker build contexts + Liquibase changelogs)
 *   - running the REAL charts chart-db Liquibase changelogs against a container DB
 *   - small JDBC query helpers for milestone assertions
 *   - HTTP + Keycloak client-credentials token helpers
 */
object E2eSupport {

    val mapper: ObjectMapper = jacksonObjectMapper()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // ---------------------------------------------------------------------
    // Repo root
    // ---------------------------------------------------------------------

    /**
     * Walk upward to the ROOT aggregator pom. We key on the module list (`<module>payment-domain</module>`),
     * which only the root pom has — matching on the artifactId alone would stop at e2e-tests/pom.xml,
     * since that file references the same artifactId as its <parent>.
     */
    val projectRoot: Path by lazy {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val pom = dir.resolve("pom.xml")
            if (Files.exists(pom) && Files.readString(pom).contains("<module>payment-domain</module>")) {
                return@lazy dir
            }
            dir = dir.parent
        }
        error("Could not locate project root (aggregator pom.xml) from ${Paths.get("").toAbsolutePath()}")
    }

    // ---------------------------------------------------------------------
    // Liquibase migration (real production changelogs)
    // ---------------------------------------------------------------------

    /**
     * Runs a Liquibase master changelog located in [changelogDir] against the given JDBC target.
     * Included changesets + the seed sqlFile (accounts-seed.sql) resolve relativeToChangelogFile,
     * so pointing the resource accessor at the directory is sufficient.
     */
    fun migrate(jdbcUrl: String, user: String, pass: String, changelogDir: Path, masterChangelogFile: String) {
        DriverManager.getConnection(jdbcUrl, user, pass).use { conn ->
            val database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(JdbcConnection(conn))
            DirectoryResourceAccessor(changelogDir.toFile()).use { accessor ->
                Liquibase(masterChangelogFile, accessor, database).use { lb ->
                    lb.update(Contexts(), LabelExpression())
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // JDBC query helpers
    // ---------------------------------------------------------------------

    fun <T> query(jdbcUrl: String, user: String, pass: String, sql: String, map: (ResultSet) -> T): List<T> =
        DriverManager.getConnection(jdbcUrl, user, pass).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val out = ArrayList<T>()
                    while (rs.next()) out.add(map(rs))
                    out
                }
            }
        }

    fun count(jdbcUrl: String, user: String, pass: String, sql: String): Long =
        query(jdbcUrl, user, pass, sql) { it.getLong(1) }.firstOrNull() ?: 0L

    fun scalarOrNull(jdbcUrl: String, user: String, pass: String, sql: String): String? =
        query(jdbcUrl, user, pass, sql) { it.getString(1) }.firstOrNull()

    // ---------------------------------------------------------------------
    // HTTP + Keycloak
    // ---------------------------------------------------------------------

    /** A merchant backend's token: client credentials of merchant-api-<merchant> (bundle MERCHANT, claim merchant_id). */
    fun merchantToken(merchant: String): String {
        val client = "merchant-api-$merchant"
        return token(
            listOf("grant_type" to "client_credentials", "client_id" to client, "client_secret" to "$client-secret")
        )
    }

    /** A person's token, as when logging in to the back office (client backoffice-ui). */
    fun userToken(username: String, password: String): String =
        token(
            listOf(
                "grant_type" to "password",
                "client_id" to "backoffice-ui",
                "username" to username,
                "password" to password
            )
        )

    private fun token(fields: List<Pair<String, String>>): String {
        val form = fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${PlatformStack.keycloakBaseUrl}/realms/ecommerce-platform/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(resp.statusCode() == HTTP_OK) { "Keycloak token request failed: ${resp.statusCode()} ${resp.body()}" }
        return mapper.readTree(resp.body()).get("access_token").asText()
    }

    data class HttpResult(val status: Int, val body: JsonNode?, val rawBody: String)

    fun postJson(url: String, token: String, body: String, extraHeaders: Map<String, String> = emptyMap()): HttpResult {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val resp = http.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        )
        val json = runCatching { mapper.readTree(resp.body()) }.getOrNull()
        return HttpResult(resp.statusCode(), json, resp.body())
    }

    /** Keycloak admin token (master realm), for setting up the test realm. */
    fun adminToken(keycloakBaseUrl: String, username: String, password: String): String {
        val form = "grant_type=password&client_id=admin-cli&username=${URLEncoder.encode(username, StandardCharsets.UTF_8)}" +
            "&password=${URLEncoder.encode(password, StandardCharsets.UTF_8)}"
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$keycloakBaseUrl/realms/master/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(resp.statusCode() == HTTP_OK) { "Keycloak admin login failed: ${resp.statusCode()} ${resp.body()}" }
        return mapper.readTree(resp.body()).get("access_token").asText()
    }

    /** One Keycloak admin API call; fails on any non-2xx answer. Returns the response body. */
    fun adminCall(method: String, url: String, adminToken: String, jsonBody: String?): String {
        val builder = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer $adminToken")
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header(
                "Content-Type",
                "application/json"
            ).method(method, HttpRequest.BodyPublishers.ofString(jsonBody))
        }
        val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        check(
            resp.statusCode() / 100 == 2
        ) { "Keycloak admin $method $url failed: ${resp.statusCode()} ${resp.body()}" }
        return resp.body()
    }

    /** GET with an optional bearer token (null = no Authorization header). */
    fun getJson(url: String, token: String?): HttpResult {
        val builder = HttpRequest.newBuilder().uri(URI.create(url)).GET()
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val json = runCatching { mapper.readTree(resp.body()) }.getOrNull()
        return HttpResult(resp.statusCode(), json, resp.body())
    }

    // ---------------------------------------------------------------------
    // Payments through the merchant API
    // ---------------------------------------------------------------------

    /** A marketplace payment of 3000 for one seller (2640) + 12% commission (360). Returns the response. */
    fun createPayment(token: String, merchant: String, seller: String, orderId: String): HttpResult =
        postJson(
            url = "${PlatformStack.paymentServiceBaseUrl}/api/v1/payments",
            token = token,
            body = """
                {
                  "orderId": "$orderId",
                  "buyerId": "BUYER-$orderId",
                  "merchantAccount": "$merchant",
                  "processingModel": "MARKETPLACE",
                  "totalAmount": { "quantity": 3000, "currency": "EUR" },
                  "splits": [
                    { "type": "BalanceAccount", "account": "$seller", "amount": { "quantity": 2640, "currency": "EUR" }},
                    { "type": "Commission", "amount": { "quantity": 360, "currency": "EUR" }}
                  ]
                }
            """.trimIndent(),
            extraHeaders = mapOf("Idempotency-Key" to newUuidV7())
        )

    fun authorize(token: String, publicPaymentIntentId: String): HttpResult =
        postJson("${PlatformStack.paymentServiceBaseUrl}/api/v1/payments/$publicPaymentIntentId/authorize", token, "{}")

    /** Creates and authorizes a payment, waits until the back office has it, returns its central payment id. */
    fun authorizedPayment(token: String, merchant: String, seller: String, orderId: String): Long {
        val create = createPayment(token, merchant, seller, orderId)
        check(create.status == HTTP_CREATED) { "createPayment failed: ${create.status} ${create.rawBody}" }
        val publicId = create.body!!.get("paymentIntentId").asText()
        val authorize = authorize(token, publicId)
        check(authorize.status == HTTP_OK) { "authorize failed: ${authorize.status} ${authorize.rawBody}" }
        var paymentId: String? = null
        val deadline = System.currentTimeMillis() + 60_000
        while (paymentId == null && System.currentTimeMillis() < deadline) {
            paymentId = scalarOrNull(
                PlatformStack.centralJdbcUrl, PlatformStack.dbUser, PlatformStack.dbPass,
                "SELECT payment_id FROM transactions WHERE public_payment_intent_id = '$publicId'"
            )
            if (paymentId == null) {
                Thread.sleep(500)
            }
        }
        checkNotNull(paymentId) { "payment $publicId never reached the back office (transactions)" }
        return paymentId.toLong()
    }

    // ---------------------------------------------------------------------
    // Clean state
    // ---------------------------------------------------------------------

    /**
     * Brings the platform back to "seed only": no payments, no ledger, all balances 0. Keeps the schema, the seed
     * (`accounts`) and Keycloak.
     *
     * First waits until nothing is in flight (both outboxes fully sent, no new postings for 3 s): work of an
     * earlier test that lands after the reset would otherwise count in the next test's balances.
     */
    fun resetState() {
        val deadline = System.currentTimeMillis() + 120_000
        var lastPostings = -1L
        var quietSince = System.currentTimeMillis()
        while (true) {
            val edgeUnsent = count(
                PlatformStack.edgeJdbcUrl,
                PlatformStack.dbUser,
                PlatformStack.dbPass,
                "SELECT count(*) FROM outbox_event WHERE status <> 'SENT'"
            )
            val centralUnsent = count(
                PlatformStack.centralJdbcUrl,
                PlatformStack.dbUser,
                PlatformStack.dbPass,
                "SELECT count(*) FROM outbox_event WHERE status <> 'SENT'"
            )
            val postings = count(
                PlatformStack.centralJdbcUrl,
                PlatformStack.dbUser,
                PlatformStack.dbPass,
                "SELECT count(*) FROM postings"
            )
            if (edgeUnsent > 0 || centralUnsent > 0 || postings != lastPostings) {
                lastPostings = postings
                quietSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - quietSince >= 3_000) {
                break
            }
            check(System.currentTimeMillis() < deadline) {
                "platform never became quiet: edge unsent=$edgeUnsent, central unsent=$centralUnsent, postings still changing"
            }
            Thread.sleep(500)
        }

        execute(PlatformStack.edgeJdbcUrl, "TRUNCATE outbox_event, payment_intents, idempotency_keys")
        execute(
            PlatformStack.centralJdbcUrl,
            "TRUNCATE outbox_event, payments, payment_tx, journal_entries, postings, transfers, " +
                "transactions, transaction_splits, account_balances CASCADE"
        )
        // balance deltas, dedupe and idempotency keys, cached account profiles
        val flush = PlatformStack.redis.execInContainer("redis-cli", "FLUSHALL")
        check(flush.exitCode == 0) { "redis FLUSHALL failed: ${flush.stderr}" }
    }

    private fun execute(jdbcUrl: String, sql: String) {
        DriverManager.getConnection(jdbcUrl, PlatformStack.dbUser, PlatformStack.dbPass).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(sql)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Kafka
    // ---------------------------------------------------------------------

    /** How many records were ever written to the topic: the sum of its partitions' end offsets. */
    fun topicRecordCount(bootstrapServers: String, topic: String): Long {
        val props = java.util.Properties()
        props["bootstrap.servers"] = bootstrapServers
        org.apache.kafka.clients.admin.Admin.create(props).use { admin ->
            val description = admin.describeTopics(listOf(topic)).allTopicNames().get().getValue(topic)
            val request = HashMap<org.apache.kafka.common.TopicPartition, org.apache.kafka.clients.admin.OffsetSpec>()
            for (partition in description.partitions()) {
                request[org.apache.kafka.common.TopicPartition(topic, partition.partition())] =
                    org.apache.kafka.clients.admin.OffsetSpec.latest()
            }
            var total = 0L
            for (offset in admin.listOffsets(request).all().get().values) {
                total += offset.offset()
            }
            return total
        }
    }

    // ---------------------------------------------------------------------
    // UUIDv7 idempotency key (createPayment requires @ValidUuidV7)
    // ---------------------------------------------------------------------

    fun newUuidV7(): String {
        val ts = System.currentTimeMillis() and 0xFFFFFFFFFFFFL // 48 bits
        val randA = Random.nextInt(0x1000) // 12 bits
        val randB = Random.nextLong() and 0x3FFFFFFFFFFFFFFFL // 62 bits
        val msb = (ts shl 16) or (0x7L shl 12) or randA.toLong() // version 7
        val lsb = (0x2L shl 62) or randB // variant 10
        return UUID(msb, lsb).toString()
    }
}
