package com.dogancaglar.paymentservice.infra.adapter.outbound.psp.simulator

import io.opentelemetry.instrumentation.annotations.WithSpan
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.random.Random

@Component
class AuthorizationNetworkSimulator(
    private val config: AuthorizationSimulationProperties
) {
    private val logger = LoggerFactory.getLogger(AuthorizationNetworkSimulator::class.java)

    private val active: AuthorizationSimulationProperties.ScenarioConfig
        get() = config.scenarios[config.scenario]
            ?: error("No scenario config for ${config.scenario}")

    @WithSpan("AuthorizationNetworkSimulator.simulate")
    fun simulate() {
        val sc = active
        logger.debug("Selected scenario: ${config.scenario}")
        if (sc.timeouts.enabled && Random.nextInt(PERCENT) < sc.timeouts.probability) {
            logger.warn(
                "💥 [${
                    config.scenario
                }] Simulated PSP timeout"
            )
            Thread.sleep(SIMULATED_TIMEOUT_MS)
        }

        // 2) latency buckets
        val roll = Random.nextInt(PERCENT)
        val latency = when {
            roll < sc.latency.fast.probability -> Random.nextLong(
                sc.latency.fast.minMs,
                sc.latency.fast.maxMs
            ) // fast path
            roll < sc.latency.fast.probability + sc.latency.moderate.probability -> Random.nextLong(
                sc.latency.moderate.minMs,
                sc.latency.moderate.maxMs
            )

            roll < sc.latency.slow.probability + sc.latency.moderate.probability + sc.latency.fast.probability ->
                Random.nextLong(
                    sc.latency.slow.minMs,
                    sc.latency.slow.maxMs
                )

            else -> FALLBACK_LATENCY_MS // this never happens
        }
        logger.debug("🕒 [${config.scenario}] Latency ${latency}ms (roll=$roll)")
        Thread.sleep(latency)
    }

    private companion object {
        const val PERCENT = 100 // a roll is a percentage
        const val SIMULATED_TIMEOUT_MS = 10_000L
        const val FALLBACK_LATENCY_MS = 5000L
    }
}
