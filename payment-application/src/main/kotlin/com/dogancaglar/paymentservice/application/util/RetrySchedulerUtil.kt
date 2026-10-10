package com.dogancaglar.paymentservice.application.util

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * When the next retry runs: exponential backoff with "equal jitter" (AWS, "Exponential Backoff And Jitter"): the wait
 * doubles per attempt from a minimum, is capped at a maximum, and half of it is random. The fixed half guarantees a
 * minimum wait; the random half spreads the retries of payments that failed at the same moment, so a recovering PSP
 * doesn't get them all at once.
 * Equal jitter on purpose, not the article's favourite "full jitter" (random between 0 and the backoff): the wait is
 * there to give the PSP room, and full jitter can retry almost immediately.
 */
object RetrySchedulerUtil {

    /** The wait before retry [attempt] (1 = the first retry), between [minDelayMs] and [maxDelayMs]. */
    fun calculateNextAttemptInMs(attempt: Int, minDelayMs: Long, maxDelayMs: Long): Long {
        var exponent = attempt - 1
        if (exponent < 0) {
            exponent = 0
        }
        val exponential = (minDelayMs * 2.0.pow(exponent)).toLong()
        val capped = min(exponential, maxDelayMs)
        return capped / 2 + Random.Default.nextLong(capped / 2 + 1)
    }
}
