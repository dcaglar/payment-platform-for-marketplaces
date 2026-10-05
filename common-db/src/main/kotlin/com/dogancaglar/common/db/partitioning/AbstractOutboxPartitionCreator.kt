package com.dogancaglar.common.db.partitioning

import com.dogancaglar.common.time.Utc
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

abstract class AbstractOutboxPartitionCreator(
    protected val jdbcTemplate: JdbcTemplate
) {
    protected val logger = LoggerFactory.getLogger(javaClass)
    protected val partitionFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")
    protected val sqlFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun ensureCurrentAndNext() {
        val now = Utc.nowLocalDateTime()
        val start = windowStart(now)
        // current window
        ensurePartitionExists(start, start.plusMinutes(PARTITION_SIZE_MIN))
        // next window
        ensurePartitionExists(start.plusMinutes(PARTITION_SIZE_MIN), start.plusMinutes(PARTITION_SIZE_MIN * 2))
    }

    protected fun ensurePartitionExists(from: LocalDateTime, to: LocalDateTime) {
        val partitionName = "outbox_event_${from.format(partitionFormatter)}"
        val fromStr = from.format(sqlFormatter)
        val toStr = to.format(sqlFormatter)

        val sql = """
            CREATE TABLE IF NOT EXISTS $partitionName
            PARTITION OF outbox_event
            FOR VALUES FROM ('$fromStr') TO ('$toStr');
        """.trimIndent()

        // a failure propagates: the scheduled job counts it (maintenance error metric) and Spring logs it;
        // swallowing it here would hide a missing partition until outbox inserts start failing
        jdbcTemplate.execute(sql)
        /** 2) Immediately disable autovacuum on the child */
        jdbcTemplate.execute("""ALTER TABLE $partitionName SET (autovacuum_enabled = false);""")
        logger.info("Ensured partition exists: $partitionName for [$fromStr, $toStr)")
        logger.debug("Disabled autovacuum on child partition: $partitionName")
    }

    fun pruneOldPartitions() {
        val now = Utc.nowLocalDateTime()
        val currWindowStart = windowStart(now)

        val sql = """
        DO ${'$'}${'$'}
        DECLARE
            part record;
            new_count integer;
            part_end_time timestamp;
            curr_window_start timestamp := '$currWindowStart';
        BEGIN
            FOR part IN
                SELECT inhrelid::regclass AS partition_name
                FROM pg_inherits
                WHERE inhparent = 'outbox_event'::regclass
            LOOP
                -- Parse partition end time (name: outbox_event_YYYYMMDD_HHMM)
                part_end_time := to_timestamp(
                    substring(part.partition_name::text from 'outbox_event_(\d{8}_\d{4})'),
                    'YYYYMMDD_HH24MI'
                ) + interval '30 minutes';

                -- Only prune partitions strictly before current window
                IF part_end_time <= curr_window_start THEN
                    EXECUTE format('SELECT count(*) FROM %I WHERE status IN (%L, %L)', part.partition_name, 'NEW', 'PROCESSING') INTO new_count;
                    IF new_count = 0 THEN
                        RAISE NOTICE 'Dropping partition: %', part.partition_name;
                        EXECUTE format('ALTER TABLE outbox_event DETACH PARTITION %I', part.partition_name);
                        EXECUTE format('DROP TABLE %I', part.partition_name);
                    END IF;
                END IF;
            END LOOP;
        END ${'$'}${'$'};
        """.trimIndent()

        // a failure propagates to the scheduled job (counted + logged there)
        jdbcTemplate.execute(sql)
        logger.debug("Pruned old partitions up to $currWindowStart")
    }

    fun vacuumOldPartitionsWithNewRows() {
        val now = Utc.nowLocalDateTime()
        val currWindowStart = windowStart(now)
        val nextWindowStart = currWindowStart.plusMinutes(PARTITION_SIZE_MIN)

        val currPartitionName = "outbox_event_${currWindowStart.format(partitionFormatter)}"
        val nextPartitionName = "outbox_event_${nextWindowStart.format(partitionFormatter)}"

        logger.debug("Vacuum check for partitions (skipping: $currPartitionName and $nextPartitionName)")

        val partitionNames: List<String> = jdbcTemplate.queryForList(
            """
            SELECT inhrelid::regclass::text AS partition_name
            FROM pg_inherits
            WHERE inhparent = 'outbox_event'::regclass
            """,
            String::class.java
        )

        for (partitionName in partitionNames) {
            if (partitionName == currPartitionName || partitionName == nextPartitionName) continue

            val newCount: Int? = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM $partitionName WHERE status IN ('NEW', 'PROCESSING')",
                Int::class.java
            )
            if ((newCount ?: 0) > 0) {
                logger.debug("VACUUM: $partitionName ($newCount NEW/PROCESSING rows remaining)")
                try {
                    jdbcTemplate.execute("VACUUM $partitionName")
                } catch (ex: DataAccessException) {
                    // best effort per partition: one failing VACUUM must not stop the others
                    logger.warn("VACUUM failed for $partitionName: ${ex.message}", ex)
                }
            }
        }
    }

    /** The start of the partition window [now] falls in (windows of PARTITION_SIZE_MIN minutes). */
    private fun windowStart(now: LocalDateTime): LocalDateTime {
        val windowMinutes = PARTITION_SIZE_MIN.toInt()
        return now.withMinute((now.minute / windowMinutes) * windowMinutes).withSecond(0).withNano(0)
    }

    private companion object {
        const val PARTITION_SIZE_MIN = 30L // one partition per 30 minutes
    }
}
