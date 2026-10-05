package com.dogancaglar.paymentservice.infra.adapter.outbound.psp.simulator

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

enum class PspAuthorizationScenario { NORMAL }

@Configuration
@ConfigurationProperties(prefix = "psp.authorization.simulation")
class AuthorizationSimulationProperties {
    var currentScenario: String? = null
    val scenario: PspAuthorizationScenario
        get() = PspAuthorizationScenario.valueOf(currentScenario ?: PspAuthorizationScenario.NORMAL.name)

    // now wrap your existing blocks under a map of named configs
    var scenarios: Map<PspAuthorizationScenario, ScenarioConfig> = emptyMap()

    class ScenarioConfig {
        var timeouts: TimeoutConfig = TimeoutConfig()
        var response: ResponseDistribution = ResponseDistribution()
        var latency: LatencyConfig = LatencyConfig()
        // ... same as before
    }

    class TimeoutConfig {
        var enabled: Boolean = true
        var probability: Int = DEFAULT_TIMEOUT_PERCENT
    }

    class LatencyConfig {
        var fast = LatencyBucket()
        var moderate = LatencyBucket()
        var slow = LatencyBucket()
    }

    class LatencyBucket {
        var probability: Int = 0
        var minMs: Long = 0
        var maxMs: Long = 0
    }

    class ResponseDistribution {
        var successful: Int = DEFAULT_SUCCESSFUL_PERCENT
        var retryable: Int = DEFAULT_RETRYABLE_PERCENT
        var statusCheck: Int = 0 // 10% of responses
        var nonRetryable: Int = DEFAULT_NON_RETRYABLE_PERCENT
    }
    // existing inner classes TimeoutConfig, LatencyConfig, ResponseDistribution…

    private companion object {
        const val DEFAULT_TIMEOUT_PERCENT = 5
        const val DEFAULT_SUCCESSFUL_PERCENT = 80
        const val DEFAULT_RETRYABLE_PERCENT = 17
        const val DEFAULT_NON_RETRYABLE_PERCENT = 3
    }
}
