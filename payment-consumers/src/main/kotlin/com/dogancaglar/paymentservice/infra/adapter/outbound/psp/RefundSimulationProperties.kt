package com.dogancaglar.paymentservice.infra.adapter.outbound.psp

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

enum class PspRefundScenario { NORMAL, PEAK, DEGRADED, BEST_PSP_EVER }

@Configuration
@ConfigurationProperties(prefix = "psp.refund.simulation")
class RefundSimulationProperties {
    var currentScenario: String? = null
    val scenario: PspRefundScenario
        get() = PspRefundScenario.valueOf(currentScenario ?: PspRefundScenario.NORMAL.name)

    // now wrap your existing blocks under a map of named configs
    var scenarios: Map<PspRefundScenario, ScenarioConfig> = emptyMap()

    class ScenarioConfig {
        var timeouts: TimeoutConfig = TimeoutConfig()
        var response: ResponseDistribution = ResponseDistribution()
        var latency: LatencyConfig = LatencyConfig()
        // ... same as before
    }

    class TimeoutConfig {
        var enabled: Boolean = true
        var probability: Int = 5 // default: 5% chance of timeout
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
        var successful: Int = 80 // 60% of responses
        var retryable: Int = 17 // 25% of responses
        var nonRetryable: Int = 3 // 5% of responses
    }
    // existing inner classes TimeoutConfig, LatencyConfig, ResponseDistribution…
}
