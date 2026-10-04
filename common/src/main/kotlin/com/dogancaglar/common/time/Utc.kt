package com.dogancaglar.common.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

object Utc {
    val clock: Clock = Clock.systemUTC()

    fun nowInstant(): Instant = Instant.now(clock)

    fun nowLocalDateTime(): LocalDateTime = LocalDateTime.now(clock)

    fun toInstant(localDateTime: LocalDateTime): Instant =
        localDateTime.toInstant(ZoneOffset.UTC)

    fun fromInstant(instant: Instant): LocalDateTime =
        LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
}
