package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.common.db.typehandler.InstantTypeHandler
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.CentralOutboxRelayMapper
import com.dogancaglar.paymentservice.util.TestDatabases
import org.apache.ibatis.exceptions.PersistenceException
import org.apache.ibatis.mapping.Environment
import org.apache.ibatis.session.SqlSession
import org.apache.ibatis.session.SqlSessionFactory
import org.apache.ibatis.session.SqlSessionFactoryBuilder
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import java.time.Instant
import java.util.TimeZone

/**
 * Integration tests for CentralOutboxRelayMapper.xml — the outbox claim state machine
 * (NEW → PROCESSING → SENT) — against real Postgres whose schema comes from the REAL
 * production Liquibase changelog (charts/central-db/db/changelog.central.xml), applied by
 * the shared TestDatabases fixture (common-test). No hand-maintained test schema exists:
 * changelog changes flow into this suite automatically.
 *
 * Validates, per statement:
 * - findEligible: T_safe boundary (inclusive), oldest-first selection, ascending result
 *   order, claim column contract, hydration round-trip, SKIP LOCKED disjointness across
 *   sessions, cross-partition global ordering, non-UTC-JVM behavior, and the NULL-parent
 *   poison-pill failure mode
 * - reclaimStuckClaims: strict staleness threshold, status scoping, zero threshold
 * - markDispatched: terminal transition, composite-key contract, and the DOCUMENTED
 *   absence of claimed_by/status guards
 * - unclaimSpecific: the claimed_by anti-steal guard and its interleavings
 * - computeTSafe / deleteWatermark: slowest-edge-wins, fail-open on empty table
 * - cross-statement walks: lifecycle, crash/duplicate window, clean failure, and the
 *   per-aggregate ordering-responsibility boundary
 *
 * Bootstrap is plain MyBatis (no Spring): the SKIP LOCKED tests need two independent
 * connections with manual transaction control. InstantTypeHandler is registered exactly
 * as CommonDbAutoConfiguration does in production.
 *
 * SEEDING RULE: production guarantees non-null parent_event_id (EventEnvelopeFactory:
 * `parentEventId ?: id`); all rows here follow it except the dedicated poison-pill test.
 *
 * Runs via `mvn clean verify -pl payment-central-relay` (Failsafe, @Tag "integration").
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CentralOutboxRelayMapperIntegrationTest {

    private val db = TestDatabases.centralDb()
    private lateinit var sqlSessionFactory: SqlSessionFactory

    private val base: Instant = Instant.parse("2026-07-01T12:00:00Z")
    private val tSafe: Instant = Instant.parse("2026-07-01T13:00:00Z")

    @BeforeAll
    fun boot() {
        val ds = PGSimpleDataSource().apply {
            setURL(db.jdbcUrl)
            user = db.username
            password = db.password
        }
        val config = org.apache.ibatis.session.Configuration(Environment("it", JdbcTransactionFactory(), ds))
        // mirror production registration (CommonDbAutoConfiguration exposes it as a bean)
        config.typeHandlerRegistry.register(Instant::class.java, InstantTypeHandler())
        config.addMapper(CentralOutboxRelayMapper::class.java)
        sqlSessionFactory = SqlSessionFactoryBuilder().build(config)

        // a second, non-default partition so cross-partition claims are exercised
        jdbc(
            """CREATE TABLE IF NOT EXISTS outbox_event_june PARTITION OF outbox_event
               FOR VALUES FROM ('2026-06-01 00:00:00') TO ('2026-06-08 00:00:00')"""
        )
    }

    @BeforeEach
    fun clean() = TestDatabases.truncateAll(db)

    // ------------------------------------------------------------------ findEligible

    @Test
    fun `findEligible should claim rows at or before tSafe and never after it`() {
        insertOutbox(oeid = 1, createdAt = tSafe.minusSeconds(60))
        insertOutbox(oeid = 2, createdAt = tSafe)                        // exactly AT tSafe → inclusive
        insertOutbox(oeid = 3, createdAt = tSafe.plusMillis(1))          // 1ms after → excluded

        val claimed = withMapper { it.findEligible(tSafe, 100, "w-1") }

        assertEquals(listOf(1L, 2L), claimed.map { c -> c.oeid })
        assertEquals("NEW", scalar("SELECT status FROM outbox_event WHERE oeid = 3"))
    }

    @Test
    fun `findEligible should only claim NEW rows`() {
        insertOutbox(oeid = 1, createdAt = base, status = "PROCESSING")
        insertOutbox(oeid = 2, createdAt = base, status = "SENT")
        insertOutbox(oeid = 3, createdAt = base, status = "NEW")

        val claimed = withMapper { it.findEligible(tSafe, 100, "w-1") }

        assertEquals(listOf(3L), claimed.map { c -> c.oeid })
    }

    @Test
    fun `findEligible should select the LOWEST oeids when batch is smaller than backlog`() {
        (1L..10L).forEach { insertOutbox(oeid = it, createdAt = base) }

        val claimed = withMapper { it.findEligible(tSafe, 5, "w-1") }

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), claimed.map { c -> c.oeid })
        assertEquals(5L, count("SELECT COUNT(*) FROM outbox_event WHERE status = 'NEW'"))
    }

    @Test
    fun `findEligible should return rows in ascending oeid order regardless of insert order`() {
        // RETURNING order is undefined in Postgres — the outer SELECT ORDER BY is load-bearing
        listOf(7L, 3L, 9L, 1L, 5L).forEach { insertOutbox(oeid = it, createdAt = base) }

        val claimed = withMapper { it.findEligible(tSafe, 100, "w-1") }

        assertEquals(listOf(1L, 3L, 5L, 7L, 9L), claimed.map { c -> c.oeid })
    }

    @Test
    fun `findEligible should set PROCESSING with claimed_by and claimed_at`() {
        insertOutbox(oeid = 1, createdAt = base)

        withMapper { it.findEligible(tSafe, 100, "w-42") }

        assertEquals("PROCESSING", scalar("SELECT status FROM outbox_event WHERE oeid = 1"))
        assertEquals("w-42", scalar("SELECT claimed_by FROM outbox_event WHERE oeid = 1"))
        assertNotNull(scalar("SELECT claimed_at FROM outbox_event WHERE oeid = 1"))
    }

    @Test
    fun `findEligible should hydrate every entity field from its own column`() {
        // Round-trip guard: MyBatis constructs the immutable entity via the all-args
        // constructor bound by COLUMN ORDER (garbage for the String trio), then the
        // resultMap's property mappings overwrite every field by NAME. This test pins
        // that phase-2 overwrite: any resultMap column/property mismatch fails here.
        jdbc(
            """INSERT INTO outbox_event
                 (oeid, partition_key, event_type, event_id, parent_event_id, aggregate_id,
                  payload, status, created_at)
               VALUES (77, 'pk-DISTINCT', 'type-DISTINCT', 'eid-DISTINCT', 'parent-DISTINCT',
                       'agg-DISTINCT', 'payload-DISTINCT', 'NEW', '${ts(base)}')"""
        )

        val e = withMapper { it.findEligible(tSafe, 100, "w-rt") }.single()

        assertEquals(77L, e.oeid)
        assertEquals("pk-DISTINCT", e.partitionKey)
        assertEquals("type-DISTINCT", e.eventType)
        assertEquals("eid-DISTINCT", e.eventId)
        assertEquals("parent-DISTINCT", e.parentEventId)
        assertEquals("agg-DISTINCT", e.aggregateId)
        assertEquals("payload-DISTINCT", e.payload)
        assertEquals("PROCESSING", e.status)
        assertEquals(base, e.createdAt)
        assertEquals("w-rt", e.claimedBy)
    }

    @Test
    fun `findEligible should not see rows already claimed by an earlier call`() {
        (1L..4L).forEach { insertOutbox(oeid = it, createdAt = base) }

        val first = withMapper { it.findEligible(tSafe, 2, "w-1") }
        val second = withMapper { it.findEligible(tSafe, 100, "w-2") }

        assertEquals(listOf(1L, 2L), first.map { c -> c.oeid })
        assertEquals(listOf(3L, 4L), second.map { c -> c.oeid })
    }

    @Test
    fun `findEligible should SKIP rows locked by a concurrent uncommitted claim instead of blocking`() {
        (1L..6L).forEach { insertOutbox(oeid = it, createdAt = base) }

        // session A claims 1-3 inside an OPEN transaction → row locks held, nothing committed
        val sessionA: SqlSession = sqlSessionFactory.openSession(false)
        try {
            val claimedByA = sessionA.getMapper(CentralOutboxRelayMapper::class.java)
                .findEligible(tSafe, 3, "w-A")
            assertEquals(listOf(1L, 2L, 3L), claimedByA.map { c -> c.oeid })

            // session B must return promptly with the NEXT rows — zero overlap, no lock wait
            val startedAt = System.nanoTime()
            val claimedByB = withMapper { it.findEligible(tSafe, 100, "w-B") }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertEquals(listOf(4L, 5L, 6L), claimedByB.map { c -> c.oeid })
            assertTrue(elapsedMs < 2_000, "SKIP LOCKED must not block (took ${elapsedMs}ms)")
        } finally {
            sessionA.commit()
            sessionA.close()
        }

        // after A commits its rows are PROCESSING — still invisible to a new claim
        assertTrue(withMapper { it.findEligible(tSafe, 100, "w-C") }.isEmpty())
    }

    @Test
    fun `findEligible should keep global oeid order across partitions`() {
        insertOutbox(oeid = 2, createdAt = Instant.parse("2026-06-03T00:00:00Z")) // june partition
        insertOutbox(oeid = 1, createdAt = base)                                  // default partition
        insertOutbox(oeid = 3, createdAt = Instant.parse("2026-06-04T00:00:00Z")) // june partition

        val claimed = withMapper { it.findEligible(tSafe, 100, "w-1") }

        assertEquals(listOf(1L, 2L, 3L), claimed.map { c -> c.oeid })
    }

    @Test
    fun `findEligible should behave identically under a non-UTC JVM default timezone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Amsterdam"))
            insertOutbox(oeid = 1, createdAt = tSafe)               // boundary row, inclusive
            insertOutbox(oeid = 2, createdAt = tSafe.plusSeconds(1))

            val claimed = withMapper { it.findEligible(tSafe, 100, "w-tz") }

            assertEquals(listOf(1L), claimed.map { c -> c.oeid })
            assertEquals(tSafe, claimed.single().createdAt)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `findEligible with a NULL parent_event_id row throws and leaves the row PROCESSING - poison pill documented`() {
        // DESIGN NOTE, not a regression guard. parent_event_id is nullable in the DDL, but
        // the entity constructor cannot accept null for the String trio's positional bind —
        // hydration throws AFTER the claim UPDATE already committed (autocommit). The row is
        // stranded in PROCESSING → reclaimed → re-claimed → throws again: a relay wedge.
        // Production is protected only by EventEnvelopeFactory's `parentEventId ?: id`.
        jdbc(
            """INSERT INTO outbox_event
                 (oeid, partition_key, event_type, event_id, parent_event_id, aggregate_id,
                  payload, status, created_at)
               VALUES (66, 'pk', 'evt', 'eid-66', NULL, 'agg', '{}', 'NEW', '${ts(base)}')"""
        )

        assertThrows(PersistenceException::class.java) {
            withMapper { it.findEligible(tSafe, 100, "w-poison") }
        }

        assertEquals("PROCESSING", scalar("SELECT status FROM outbox_event WHERE oeid = 66"))
    }

    // ------------------------------------------------------------ reclaimStuckClaims

    @Test
    fun `reclaimStuck should revert only PROCESSING rows strictly older than the threshold`() {
        insertOutbox(oeid = 1, createdAt = base, status = "PROCESSING")
        insertOutbox(oeid = 2, createdAt = base, status = "PROCESSING")
        backdateClaim(oeid = 1, secondsAgo = 121)
        backdateClaim(oeid = 2, secondsAgo = 60) // fresh claim — must survive

        val reclaimed = withMapper { it.reclaimStuckClaims(120) }

        assertEquals(1, reclaimed)
        assertEquals("NEW", scalar("SELECT status FROM outbox_event WHERE oeid = 1"))
        assertNull(scalar("SELECT claimed_by FROM outbox_event WHERE oeid = 1"))
        assertEquals("PROCESSING", scalar("SELECT status FROM outbox_event WHERE oeid = 2"))
    }

    @Test
    fun `reclaimStuck should never touch SENT or NEW rows even with ancient claimed_at`() {
        insertOutbox(oeid = 1, createdAt = base, status = "SENT")
        insertOutbox(oeid = 2, createdAt = base, status = "NEW")
        backdateClaim(oeid = 1, secondsAgo = 10_000)
        backdateClaim(oeid = 2, secondsAgo = 10_000)

        val reclaimed = withMapper { it.reclaimStuckClaims(120) }

        assertEquals(0, reclaimed)
        assertEquals("SENT", scalar("SELECT status FROM outbox_event WHERE oeid = 1"))
        assertEquals("NEW", scalar("SELECT status FROM outbox_event WHERE oeid = 2"))
    }

    @Test
    fun `reclaimStuck with zero threshold should reclaim any committed PROCESSING row`() {
        insertOutbox(oeid = 1, createdAt = base, status = "PROCESSING")
        backdateClaim(oeid = 1, secondsAgo = 1)

        assertEquals(1, withMapper { it.reclaimStuckClaims(0) })
    }

    // ---------------------------------------------------------------- countEligible

    @Test
    fun `countEligible should count NEW rows behind tSafe and saturate at 10000`() {
        jdbc(
            """INSERT INTO outbox_event
                 (oeid, partition_key, event_type, event_id, parent_event_id, aggregate_id,
                  payload, status, created_at)
               SELECT g, 'pk', 'evt', 'e-' || g, 'e-' || g, 'agg', '{}', 'NEW',
                      TIMESTAMP '2026-07-01 12:00:00'
               FROM generate_series(1, 10001) g"""
        )
        insertOutbox(oeid = 20_000, createdAt = base, status = "SENT")           // wrong status
        insertOutbox(oeid = 20_001, createdAt = tSafe.plusSeconds(60))           // after tSafe

        // documents the LIMIT 10000 cap: a 10_001-row backlog reads as 10_000 on the gauge
        assertEquals(10_000L, withMapper { it.countEligible(tSafe) })
    }

    // --------------------------------------------------------------- markDispatched

    @Test
    fun `markDispatched should set SENT and clear the claim and be idempotent`() {
        insertOutbox(oeid = 1, createdAt = base)
        withMapper { it.findEligible(tSafe, 100, "w-1") }

        withMapper { it.markDispatched(1, base) }
        withMapper { it.markDispatched(1, base) } // repeat is harmless

        assertEquals("SENT", scalar("SELECT status FROM outbox_event WHERE oeid = 1"))
        assertNull(scalar("SELECT claimed_by FROM outbox_event WHERE oeid = 1"))
        assertNull(scalar("SELECT claimed_at FROM outbox_event WHERE oeid = 1"))
    }

    @Test
    fun `markDispatched with wrong created_at should update nothing`() {
        // the composite (oeid, created_at) key is the partition-pruning contract:
        // a bug here strands rows in PROCESSING → they resurrect via reclaim → republish loop
        insertOutbox(oeid = 1, createdAt = base, status = "PROCESSING")

        withMapper { it.markDispatched(1, base.plusSeconds(1)) }

        assertEquals("PROCESSING", scalar("SELECT status FROM outbox_event WHERE oeid = 1"))
    }

    @Test
    fun `markDispatched has no claimed_by or status guard - documented current behavior`() {
        // DESIGN NOTE, not a regression guard: mark is unguarded and relies on the worker
        // only calling it after ITS OWN successful publish. These asserts pin that contract.
        insertOutbox(oeid = 1, createdAt = base)
        withMapper { it.findEligible(tSafe, 100, "w-owner") }
        withMapper { it.markDispatched(1, base) }                      // "another" worker may mark
        assertEquals("SENT", scalar("SELECT status FROM outbox_event WHERE oeid = 1"))

        insertOutbox(oeid = 2, createdAt = base)                        // never claimed
        withMapper { it.markDispatched(2, base) }
        assertEquals("SENT", scalar("SELECT status FROM outbox_event WHERE oeid = 2"))
    }

    // -------------------------------------------------------------- unclaimSpecific

    @Test
    fun `unclaimSpecific should reset to NEW only for the owning worker`() {
        insertOutbox(oeid = 1, createdAt = base)
        withMapper { it.findEligible(tSafe, 100, "w-owner") }

        withMapper { it.unclaimSpecific(1, base, "w-intruder") }        // wrong worker → no-op
        assertEquals("PROCESSING", scalar("SELECT status FROM outbox_event WHERE oeid = 1"))

        withMapper { it.unclaimSpecific(1, base, "w-owner") }
        assertEquals("NEW", scalar("SELECT status FROM outbox_event WHERE oeid = 1"))
        assertNull(scalar("SELECT claimed_by FROM outbox_event WHERE oeid = 1"))
    }

    @Test
    fun `unclaimSpecific by a stale worker should not steal a row re-claimed after reclaim`() {
        // the interleaving the guard exists for: A claims → stalls → reclaimed → B re-claims
        insertOutbox(oeid = 1, createdAt = base)
        withMapper { it.findEligible(tSafe, 100, "w-A") }
        backdateClaim(oeid = 1, secondsAgo = 121)
        withMapper { it.reclaimStuckClaims(120) }
        withMapper { it.findEligible(tSafe, 100, "w-B") }

        withMapper { it.unclaimSpecific(1, base, "w-A") }               // A wakes up late

        assertEquals("PROCESSING", scalar("SELECT status FROM outbox_event WHERE oeid = 1"))
        assertEquals("w-B", scalar("SELECT claimed_by FROM outbox_event WHERE oeid = 1"))
    }

    @Test
    fun `unclaimSpecific after markDispatched should be a no-op - SENT is terminal`() {
        insertOutbox(oeid = 1, createdAt = base)
        withMapper { it.findEligible(tSafe, 100, "w-A") }
        withMapper { it.markDispatched(1, base) }

        withMapper { it.unclaimSpecific(1, base, "w-A") }               // late failure path fires anyway

        assertEquals("SENT", scalar("SELECT status FROM outbox_event WHERE oeid = 1"))
    }

    // ------------------------------------------------- computeTSafe / deleteWatermark

    @Test
    fun `computeTSafe should return the SLOWEST edge watermark`() {
        insertWatermark("edge-0", Instant.parse("2026-07-01T10:00:00Z"))
        insertWatermark("edge-1", Instant.parse("2026-07-01T09:00:00Z")) // laggard
        insertWatermark("edge-2", Instant.parse("2026-07-01T11:00:00Z"))

        assertEquals(Instant.parse("2026-07-01T09:00:00Z"), withMapper { it.computeTSafe() })
    }

    @Test
    fun `computeTSafe should return null on empty watermarks - documents the fail-open gate`() {
        // DESIGN NOTE: the worker falls back to now() on null, silently disabling the
        // completeness gate. This test is executable documentation of that fail-open behavior.
        assertNull(withMapper { it.computeTSafe() })
    }

    @Test
    fun `deleteWatermark of the lagging edge should widen the gate and expose newer rows`() {
        insertWatermark("edge-slow", Instant.parse("2026-07-01T09:00:00Z"))
        insertWatermark("edge-fast", Instant.parse("2026-07-01T11:00:00Z"))
        insertOutbox(oeid = 1, createdAt = Instant.parse("2026-07-01T10:00:00Z")) // above old T_safe

        val gateBefore = withMapper { it.computeTSafe() }!!
        val before = withMapper { it.findEligible(gateBefore, 100, "w-1") }
        assertTrue(before.isEmpty(), "row above the slow edge's watermark must be ineligible")

        withMapper { it.deleteWatermark("edge-slow") }

        val gateAfter = withMapper { it.computeTSafe() }!!
        val after = withMapper { it.findEligible(gateAfter, 100, "w-1") }
        assertEquals(listOf(1L), after.map { c -> c.oeid })
    }

    // ------------------------------------------------------- cross-statement walks

    @Test
    fun `lifecycle walk - NEW to claim to mark reaches terminal SENT`() {
        insertOutbox(oeid = 1, createdAt = base)

        val claimed = withMapper { it.findEligible(tSafe, 100, "w-1") }
        assertEquals("PROCESSING", scalar("SELECT status FROM outbox_event WHERE oeid = 1"))

        withMapper { it.markDispatched(claimed.single().oeid, base) }
        assertEquals("SENT", scalar("SELECT status FROM outbox_event WHERE oeid = 1"))
        assertTrue(withMapper { it.findEligible(tSafe, 100, "w-2") }.isEmpty())
    }

    @Test
    fun `crash walk - unmarked claim is parked until reclaim then re-claimed by a new worker`() {
        // this IS the at-least-once duplicate window, executable:
        // publish succeeded but the worker died before markDispatched
        insertOutbox(oeid = 1, createdAt = base)
        withMapper { it.findEligible(tSafe, 100, "w-crashed") }

        // parked: invisible to every poller while PROCESSING
        assertTrue(withMapper { it.findEligible(tSafe, 100, "w-other") }.isEmpty())

        backdateClaim(oeid = 1, secondsAgo = 121)
        assertEquals(1, withMapper { it.reclaimStuckClaims(120) })

        val reclaimed = withMapper { it.findEligible(tSafe, 100, "w-second") }
        assertEquals(listOf(1L), reclaimed.map { c -> c.oeid })
        assertEquals("w-second", scalar("SELECT claimed_by FROM outbox_event WHERE oeid = 1"))
    }

    @Test
    fun `clean-failure walk - unclaimed row is immediately re-eligible without lease wait`() {
        insertOutbox(oeid = 1, createdAt = base)
        withMapper { it.findEligible(tSafe, 100, "w-1") }

        withMapper { it.unclaimSpecific(1, base, "w-1") }               // publish threw → clean unclaim

        val next = withMapper { it.findEligible(tSafe, 100, "w-2") }
        assertEquals(listOf(1L), next.map { c -> c.oeid })
    }

    @Test
    fun `claim does not exclude in-flight aggregates - ordering relies on choreography causality`() {
        // DESIGN BOUNDARY, not a bug: the SQL claims a NEWER event of an aggregate whose
        // OLDER event is still PROCESSING (in flight). Per-aggregate ordering is protected
        // elsewhere: (L1) per-batch groupBy+sequential await in the worker, (L2) choreography
        // causality (event N+1 is only appended after N was consumed), (L3) partitionKey →
        // one Kafka partition per payment, (L4) idempotent producer.
        insertOutbox(oeid = 10, createdAt = base, aggregateId = "agg-X")
        withMapper { it.findEligible(tSafe, 100, "w-inflight") }        // oeid 10 now in flight

        insertOutbox(oeid = 11, createdAt = base.plusSeconds(1), aggregateId = "agg-X")
        val nextPoll = withMapper { it.findEligible(tSafe, 100, "w-next") }

        assertEquals(listOf(11L), nextPoll.map { c -> c.oeid })
    }

    // --------------------------------------------------------------- test helpers

    private fun <T> withMapper(block: (CentralOutboxRelayMapper) -> T): T =
        sqlSessionFactory.openSession(true).use { session ->
            block(session.getMapper(CentralOutboxRelayMapper::class.java))
        }

    /** Rows follow the production invariant: parent_event_id is NEVER null (root ⇒ own id). */
    private fun insertOutbox(
        oeid: Long,
        createdAt: Instant,
        status: String = "NEW",
        aggregateId: String = "agg-1",
    ) = jdbc(
        """INSERT INTO outbox_event
             (oeid, partition_key, event_type, event_id, parent_event_id, aggregate_id,
              payload, status, created_at)
           VALUES ($oeid, 'pk-$aggregateId', 'payment_authorized', 'evt-$oeid', 'evt-$oeid',
                   '$aggregateId', '{}', '$status', '${ts(createdAt)}')"""
    )

    private fun insertWatermark(edgeNodeId: String, forwardedUpTo: Instant) = jdbc(
        "INSERT INTO edge_watermarks (edge_node_id, forwarded_up_to) VALUES ('$edgeNodeId', '${ts(forwardedUpTo)}')"
    )

    private fun backdateClaim(oeid: Long, secondsAgo: Int) = jdbc(
        """UPDATE outbox_event
           SET claimed_at = (now() AT TIME ZONE 'UTC') - interval '$secondsAgo seconds',
               claimed_by = COALESCE(claimed_by, 'w-backdated')
           WHERE oeid = $oeid"""
    )

    /**
     * Instant → SQL literal text for `timestamp without time zone` columns.
     * Utc.fromInstant gives the UTC LocalDateTime; its ISO text (e.g. 2026-07-01T12:00:00)
     * is directly parseable by Postgres — no java.sql.Timestamp needed.
     */
    private fun ts(instant: Instant): String =
        Utc.fromInstant(instant).toString()

    private fun jdbc(sql: String) {
        TestDatabases.connection(db).use { conn ->
            conn.createStatement().use { it.execute(sql) }
        }
    }

    private fun scalar(sql: String): Any? =
        TestDatabases.connection(db).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null }
            }
        }

    private fun count(sql: String): Long =
        (scalar(sql) as Number).toLong()
}
