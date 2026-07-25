package com.dogancaglar.e2e

import dasniko.testcontainers.keycloak.KeycloakContainer
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.lifecycle.Startables
import org.testcontainers.utility.DockerImageName
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Properties

/**
 * The full platform topology as real containers on one Docker network.
 *
 * Lifecycle (ordered, because migration + topic creation must sit between infra and services):
 *   1. start infra: keycloak, edge-db, central-db, kafka, redis
 *   2. migrate edge-db + central-db from the REAL charts chart-db Liquibase changelogs
 *   3. create the core Kafka topics (gateway.capture.requested is NOT in any app spec)
 *   4. start the 4 services (images built from each module's Dockerfile)
 *
 * The test JVM (host) reaches DBs / keycloak / payment-service via mapped ports;
 * the services reach each other via in-network aliases.
 */
object PlatformStack {

    private const val PG_USER = "test"
    private const val PG_PASS = "test"

    // In-network addresses the services use to reach each other.
    private const val REDIS_URL = "redis://redis-master:6379"
    private const val KAFKA_INTERNAL = "kafka:19092"
    private const val CENTRAL_URL_INTERNAL =
        "jdbc:postgresql://central-db-postgresql:5432/central-db?options=-c%20timezone=UTC"
    // Must match the edge-db network alias below; edge-workers derives this exact host.
    private const val EDGE_URL_INTERNAL =
        "jdbc:postgresql://payment-edge-cell-0.payment-edge-cell-headless:5432/edge-db?options=-c%20timezone=UTC"
    private const val ISSUER_INTERNAL = "http://keycloak:8080/realms/ecommerce-platform"

    val network: Network = Network.newNetwork()

    // Streams each service container's stdout into the test log (prefixed per service) so CI
    // surfaces what the consumers/relay actually did — e.g. diagnosing an M10 allocation stall.
    private val containerLog = LoggerFactory.getLogger("e2e.container")

    // ---------------------------------------------------------------- infra
    val keycloak: KeycloakContainer = KeycloakContainer("quay.io/keycloak/keycloak:23.0.7")
        .withRealmImportFile("keycloak/ecommerce-platform-realm.json")
        .withNetwork(network)
        .withNetworkAliases("keycloak")
        // Pin the issued token issuer to the in-network URL so payment-service (which validates
        // against http://keycloak:8080/...) accepts tokens the host test fetched via the mapped port.
        .withEnv("KC_HOSTNAME_URL", "http://keycloak:8080")
        .withEnv("KC_HOSTNAME_STRICT", "false")
        .withEnv("KC_HOSTNAME_STRICT_BACKCHANNEL", "false")
        // dasniko 3.4.0 probes /health/started on mgmt port 9000 (KC 25+); KC 23 has no 9000,
        // so wait on the boot log line instead — version-independent.
        .waitingFor(
            org.testcontainers.containers.wait.strategy.Wait
                .forLogMessage(".*Listening on: http://0.0.0.0:8080.*", 1)
                .withStartupTimeout(Duration.ofMinutes(3))
        ) as KeycloakContainer

    val edgeDb: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:15"))
        .withDatabaseName("edge-db")
        .withUsername(PG_USER)
        .withPassword(PG_PASS)
        .withNetwork(network)
        // edge-workers builds jdbc://payment-edge-cell-0.payment-edge-cell-headless:5432/edge-db
        .withNetworkAliases(
            "payment-edge-cell-0.payment-edge-cell-headless",
            "payment-edge-cell-headless",
            "edge-db"
        )

    val centralDb: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16"))
        .withDatabaseName("central-db")
        .withUsername(PG_USER)
        .withPassword(PG_PASS)
        .withNetwork(network)
        .withNetworkAliases("central-db-postgresql")

    val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))
        .withNetwork(network)
        .withNetworkAliases("kafka")
        .withListener("kafka:19092") as KafkaContainer

    val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379)
        .withNetwork(network)
        .withNetworkAliases("redis-master")

    // -------------------------------------------------------------- services
    // Reuse the already-published images as-is (no in-test build, no app-source e2e config).
    // They contain application-local.yml; we run SPRING_PROFILES_ACTIVE=local and override only the
    // infra addresses/creds below to point the app at this Testcontainers network.
    private const val IMAGE_REPO = "dcaglar1987"
    private fun imageTag(module: String) = DockerImageName.parse("$IMAGE_REPO/$module:latest")

    val paymentService: GenericContainer<*> = GenericContainer(imageTag("payment-service"))
        .withNetwork(network)
        .withNetworkAliases("payment-service")
        .withLogConsumer(Slf4jLogConsumer(containerLog).withPrefix("payment-service"))
        .withExposedPorts(8080)
        .withEnv(
            mapOf(
                "SPRING_PROFILES_ACTIVE" to "local",
                "SPRING_LIQUIBASE_ENABLED" to "false",
                "POD_NAME" to "payment-service-0",
                "EDGE_DB_URL" to EDGE_URL_INTERNAL,
                "EDGE_DB_PAYMENT_SERVICE_USERNAME" to PG_USER,
                "EDGE_DB_PAYMENT_SERVICE_PASSWORD" to PG_PASS,
                "SPRING_DATA_REDIS_URL" to REDIS_URL,
                "KEYCLOAK_ISSUER_URL" to ISSUER_INTERNAL,
                "STRIPE_API_KEY" to "sk_test_dummy"
            )
        )
        .withStartupTimeout(Duration.ofMinutes(3))
        .withStartupAttempts(1)
        .also { it.waitingFor(logWait("PaymentServiceApplication")) }

    val edgeWorkers: GenericContainer<*> = GenericContainer(imageTag("payment-edge-workers"))
        .withNetwork(network)
        .withNetworkAliases("payment-edge-workers")
        .withLogConsumer(Slf4jLogConsumer(containerLog).withPrefix("payment-edge-workers"))
        .withEnv(
            mapOf(
                "SPRING_PROFILES_ACTIVE" to "local",
                "SPRING_LIQUIBASE_ENABLED" to "false",
                // ordinal parsed from substringAfterLast("-") => "0" => edge cell 0
                "POD_NAME" to "payment-edge-cell-0",
                "EDGE_CELL_BASE_URL" to "payment-edge-cell",
                "EDGE_CELL_HEADLESS_SERVICE" to "payment-edge-cell-headless",
                "EDGE_DB_PAYMENT_EDGE_WORKERS_USERNAME" to PG_USER,
                "EDGE_DB_PAYMENT_EDGE_WORKERS_PASSWORD" to PG_PASS,
                "EDGE_DB_MAINTENANCE_USERNAME" to PG_USER,
                "EDGE_DB_MAINTENANCE_PASSWORD" to PG_PASS,
                "CENTRAL_DB_URL" to CENTRAL_URL_INTERNAL,
                "CENTRAL_DB_PAYMENT_EDGE_WORKERS_USERNAME" to PG_USER,
                "CENTRAL_DB_PAYMENT_EDGE_WORKERS_PASSWORD" to PG_PASS,
                "SPRING_DATA_REDIS_URL" to REDIS_URL
            )
        )
        .withStartupTimeout(Duration.ofMinutes(3))
        .also { it.waitingFor(logWait("PaymentEdgeWorkersApplication")) }

    val centralRelay: GenericContainer<*> = GenericContainer(imageTag("payment-central-relay"))
        .withNetwork(network)
        .withNetworkAliases("payment-central-relay")
        .withLogConsumer(Slf4jLogConsumer(containerLog).withPrefix("payment-central-relay"))
        .withEnv(
            mapOf(
                "SPRING_PROFILES_ACTIVE" to "local",
                "SPRING_LIQUIBASE_ENABLED" to "false",
                "POD_NAME" to "payment-central-relay-0",
                "CENTRAL_DB_URL" to CENTRAL_URL_INTERNAL,
                "CENTRAL_DB_PAYMENT_CENTRAL_RELAY_USERNAME" to PG_USER,
                "CENTRAL_DB_PAYMENT_CENTRAL_RELAY_PASSWORD" to PG_PASS,
                "CENTRAL_DB_MAINTENANCE_USERNAME" to PG_USER,
                "CENTRAL_DB_MAINTENANCE_PASSWORD" to PG_PASS,
                "SPRING_KAFKA_BOOTSTRAP_SERVERS" to KAFKA_INTERNAL
            )
        )
        .withStartupTimeout(Duration.ofMinutes(3))
        .also { it.waitingFor(logWait("PaymentCentralRelayApplication")) }

    val consumers: GenericContainer<*> = GenericContainer(imageTag("payment-consumers"))
        .withNetwork(network)
        .withNetworkAliases("payment-consumers")
        .withLogConsumer(Slf4jLogConsumer(containerLog).withPrefix("payment-consumers"))
        .withEnv(
            mapOf(
                "SPRING_PROFILES_ACTIVE" to "local",
                "SPRING_LIQUIBASE_ENABLED" to "false",
                "POD_NAME" to "payment-consumers-0",
                "CENTRAL_DB_URL" to CENTRAL_URL_INTERNAL,
                "CENTRAL_DB_PAYMENT_CONSUMERS_USERNAME" to PG_USER,
                "CENTRAL_DB_PAYMENT_CONSUMERS_PASSWORD" to PG_PASS,
                "SPRING_DATA_REDIS_URL" to REDIS_URL,
                "SPRING_KAFKA_BOOTSTRAP_SERVERS" to KAFKA_INTERNAL
            )
        )
        .withStartupTimeout(Duration.ofMinutes(3))
        .also { it.waitingFor(logWait("PaymentConsumersApplication")) }

    private fun logWait(mainClass: String) =
        org.testcontainers.containers.wait.strategy.Wait
            .forLogMessage(".*Started $mainClass.*", 1)
            .withStartupTimeout(Duration.ofMinutes(3))

    // ------------------------------------------------------------- accessors
    // Host-facing (mapped-port) connection details for the test's own queries.
    val edgeJdbcUrl: String get() = edgeDb.jdbcUrl
    val centralJdbcUrl: String get() = centralDb.jdbcUrl
    val dbUser get() = PG_USER
    val dbPass get() = PG_PASS
    val paymentServiceBaseUrl: String get() = "http://${paymentService.host}:${paymentService.getMappedPort(8080)}"
    val keycloakBaseUrl: String get() = keycloak.authServerUrl.trimEnd('/')

    // --------------------------------------------------------------- startup
    @Volatile private var started = false

    @Synchronized
    fun start() {
        if (started) return

        // 1. infra in parallel
        Startables.deepStart(listOf(keycloak, edgeDb, centralDb, kafka, redis)).join()

        // 2. migrate both DBs from the real production changelogs
        val edgeChangelogDir = E2eSupport.projectRoot.resolve("charts/payment-edge-cell/db")
        val centralChangelogDir = E2eSupport.projectRoot.resolve("charts/central-db/db")
        E2eSupport.migrate(edgeDb.jdbcUrl, PG_USER, PG_PASS, edgeChangelogDir, "changelog.edge.xml")
        E2eSupport.migrate(centralDb.jdbcUrl, PG_USER, PG_PASS, centralChangelogDir, "changelog.central.xml")

        // 3. create core topics (gateway.capture.requested is absent from every app spec)
        createTopics()

        // 4. start the 4 published images (payment-service first mirrors deploy order)
        Startables.deepStart(listOf(paymentService, edgeWorkers, centralRelay, consumers)).join()

        started = true
    }

    private fun createTopics() {
        val props = Properties().apply { put("bootstrap.servers", kafka.bootstrapServers) }
        Admin.create(props).use { admin ->
            val names = listOf(
                "payment.psp.results",
                "gateway.capture.requested",
                "gateway.capture.submitted",
                "journal.entries.recorded"
            )
            val topics = names.flatMap { listOf(it, "$it.DLQ") }
                .map { NewTopic(it, 12, 1.toShort()) }
            runCatching { admin.createTopics(topics).all().get() } // ignore AlreadyExists races
        }
    }
}
