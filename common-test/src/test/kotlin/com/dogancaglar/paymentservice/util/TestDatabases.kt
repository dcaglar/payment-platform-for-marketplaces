package com.dogancaglar.paymentservice.util

import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.DirectoryResourceAccessor
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

/**
 * The ONE place test databases are initialized, for every module's integration tests.
 *
 * Schema comes from the REAL production Liquibase changelogs (charts/central-db/db and
 * charts/payment-edge-cell/db) applied to a Testcontainers Postgres — the exact mechanism
 * production (the Helm liquibase job) and the e2e stack use. There is no hand-maintained
 * test schema anywhere: change a changelog and every test picks it up automatically.
 *
 * Containers are per-JVM singletons: created on first request, reused by every test class
 * in the module, cleaned up by Testcontainers/Ryuk when the JVM exits. Test isolation
 * comes from [truncateAll] in @BeforeEach, never from container restarts.
 *
 * Usage:
 *   private val db = TestDatabases.centralDb()
 *   @BeforeEach fun clean() = TestDatabases.truncateAll(db)
 *
 * Style note: written deliberately in plain, iterative Kotlin (explicit loops, explicit
 * null-checked initialization) — no dense functional one-liners. `.use { }` is kept
 * because it is exactly Java's try-with-resources.
 */
object TestDatabases {

    private const val PG_IMAGE = "postgres:17" // matches central-db chart (bitnami 18.7.6 → PG 17)

    private var centralContainer: PostgreSQLContainer<*>? = null
    private var edgeContainer: PostgreSQLContainer<*>? = null

    /** Central-db container, migrated with changelog.central.xml. Started on first call. */
    @Synchronized
    fun centralDb(): PostgreSQLContainer<*> {
        if (centralContainer == null) {
            val changelogDir = findProjectRoot().resolve("charts/central-db/db")
            centralContainer = startAndMigrate("central_db", changelogDir, "changelog.central.xml")
        }
        return centralContainer!!
    }

    /** Edge-db container, migrated with changelog.edge.xml. Started on first call. */
    @Synchronized
    fun edgeDb(): PostgreSQLContainer<*> {
        if (edgeContainer == null) {
            val changelogDir = findProjectRoot().resolve("charts/payment-edge-cell/db")
            edgeContainer = startAndMigrate("edge_db", changelogDir, "changelog.edge.xml")
        }
        return edgeContainer!!
    }

    /**
     * Truncates every application table (partitions included, via their parents) so each
     * test starts clean. Liquibase bookkeeping tables and changelog-seeded reference data
     * (account_directory) are preserved.
     */
    fun truncateAll(db: PostgreSQLContainer<*>) {
        val tableNames = ArrayList<String>()

        connection(db).use { conn ->
            conn.createStatement().use { statement ->
                val sql = """
                    SELECT c.relname
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                      AND c.relkind IN ('r', 'p')          -- plain tables + partitioned parents
                      AND c.relispartition = false          -- parents only; TRUNCATE cascades to partitions
                      AND c.relname NOT LIKE 'databasechangelog%'
                      AND c.relname <> 'account_directory'
                """
                statement.executeQuery(sql).use { resultSet ->
                    while (resultSet.next()) {
                        tableNames.add(resultSet.getString(1))
                    }
                }
            }

            if (tableNames.isEmpty()) {
                return
            }

            val quotedNames = ArrayList<String>()
            for (name in tableNames) {
                quotedNames.add("\"" + name + "\"")
            }
            val truncateSql = "TRUNCATE " + quotedNames.joinToString(", ") + " CASCADE"

            conn.createStatement().use { statement ->
                statement.execute(truncateSql)
            }
        }
    }

    /** Plain JDBC connection to a fixture container, for seeding/asserting in tests. */
    fun connection(db: PostgreSQLContainer<*>): Connection {
        return DriverManager.getConnection(db.jdbcUrl, db.username, db.password)
    }

    // ------------------------------------------------------------------ internals

    /** Walks up from the current directory until it finds the aggregator pom.xml. */
    private fun findProjectRoot(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val pom = dir.resolve("pom.xml")
            if (Files.exists(pom) && Files.readString(pom).contains("<module>payment-domain</module>")) {
                return dir
            }
            dir = dir.parent
        }
        throw IllegalStateException(
            "Could not locate project root (aggregator pom.xml) from " + Paths.get("").toAbsolutePath()
        )
    }

    private fun startAndMigrate(
        dbName: String,
        changelogDir: Path,
        masterChangelog: String
    ): PostgreSQLContainer<*> {
        val container = PostgreSQLContainer(DockerImageName.parse(PG_IMAGE))
            .withDatabaseName(dbName)
            .withUsername("test")
            .withPassword("test")
        container.start()
        migrate(container, changelogDir, masterChangelog)
        createDefaultPartitions(container)
        return container
    }

    /** Same mechanism as E2eSupport.migrate — the real changelog, applied the real way. */
    private fun migrate(db: PostgreSQLContainer<*>, changelogDir: Path, masterChangelog: String) {
        connection(db).use { conn ->
            val database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(JdbcConnection(conn))
            DirectoryResourceAccessor(changelogDir.toFile()).use { accessor ->
                Liquibase(masterChangelog, accessor, database).use { liquibase ->
                    liquibase.update(Contexts(), LabelExpression())
                }
            }
        }
    }

    /**
     * Changelogs create partitioned PARENTS only; in production, runtime maintenance jobs
     * create the time-range partitions. Tests need an insertable partition, so every
     * partitioned parent gets a DEFAULT partition here.
     */
    private fun createDefaultPartitions(db: PostgreSQLContainer<*>) {
        val parentTableNames = ArrayList<String>()

        connection(db).use { conn ->
            conn.createStatement().use { statement ->
                val sql = "SELECT c.relname FROM pg_partitioned_table p JOIN pg_class c ON c.oid = p.partrelid"
                statement.executeQuery(sql).use { resultSet ->
                    while (resultSet.next()) {
                        parentTableNames.add(resultSet.getString(1))
                    }
                }
            }

            conn.createStatement().use { statement ->
                for (parent in parentTableNames) {
                    statement.execute("CREATE TABLE IF NOT EXISTS " + parent + "_default PARTITION OF " + parent + " DEFAULT")
                }
            }
        }
    }
}
