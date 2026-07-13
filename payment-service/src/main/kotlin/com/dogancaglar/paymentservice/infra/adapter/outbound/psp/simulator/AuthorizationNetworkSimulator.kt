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
            ?: throw IllegalStateException("No scenario config for ${config.scenario}")

            @WithSpan("AuthorizationNetworkSimulator.simulate")
    fun simulate() {
        val sc = active
        logger.debug("Selected scenario: ${config.scenario}")
        if (sc.timeouts.enabled && Random.nextInt(100) < sc.timeouts.probability) {
            logger.warn(
                "💥 [${
                    config.scenario
                }] Simulated PSP timeout"
            )
            Thread.sleep(10_000)
        }

        // 2) latency buckets
        val roll = Random.nextInt(100)
        val latency = when {
            roll < sc.latency.fast.probability -> Random.nextLong(
                sc.latency.fast.minMs,
                sc.latency.fast.maxMs
            )       // fast path
            roll < sc.latency.fast.probability + sc.latency.moderate.probability -> Random.nextLong(
                sc.latency.moderate.minMs,
                sc.latency.moderate.maxMs
            )

            roll < sc.latency.slow.probability + sc.latency.moderate.probability + sc.latency.fast.probability -> Random.nextLong(
                sc.latency.slow.minMs,
                sc.latency.slow.maxMs
            )

            else -> Random.nextLong(5000, 5000)//this enver happens
        }
        logger.debug("🕒 [${config.scenario}] Latency ${latency}ms (roll=$roll)")
        Thread.sleep(latency)
    }
}
