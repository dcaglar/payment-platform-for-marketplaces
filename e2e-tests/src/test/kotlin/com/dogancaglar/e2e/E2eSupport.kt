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
     * Included changesets + loadData (account_directory.csv) resolve relativeToChangelogFile,
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

    /** Client-credentials grant against Keycloak realm ecommerce-platform, client payment-service. */
    fun fetchToken(keycloakBaseUrl: String, clientId: String = "payment-service", clientSecret: String = "payment-service-secret"): String {
        val form = listOf(
            "grant_type" to "client_credentials",
            "client_id" to clientId,
            "client_secret" to clientSecret
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }

        val req = HttpRequest.newBuilder()
            .uri(URI.create("$keycloakBaseUrl/realms/ecommerce-platform/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(resp.statusCode() == 200) { "Keycloak token request failed: ${resp.statusCode()} ${resp.body()}" }
        return mapper.readTree(resp.body()).get("access_token").asText()
    }

    data class HttpResult(val status: Int, val body: JsonNode?, val rawBody: String)

    fun postJson(url: String, token: String, body: String, extraHeaders: Map<String, String> = emptyMap()): HttpResult {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val resp = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        val json = runCatching { mapper.readTree(resp.body()) }.getOrNull()
        return HttpResult(resp.statusCode(), json, resp.body())
    }

    // ---------------------------------------------------------------------
    // UUIDv7 idempotency key (createPayment requires @ValidUuidV7)
    // ---------------------------------------------------------------------

    fun newUuidV7(): String {
        val ts = System.currentTimeMillis() and 0xFFFFFFFFFFFFL // 48 bits
        val randA = Random.nextInt(0x1000)                       // 12 bits
        val randB = Random.nextLong() and 0x3FFFFFFFFFFFFFFFL    // 62 bits
        val msb = (ts shl 16) or (0x7L shl 12) or randA.toLong() // version 7
        val lsb = (0x2L shl 62) or randB                         // variant 10
        return UUID(msb, lsb).toString()
    }
}
