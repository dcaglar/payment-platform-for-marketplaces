package com.dogancaglar.paymentservice.infra.adapter.outbound.psp.simulator

import com.dogancaglar.paymentservice.domain.exception.PspPermanentException
import com.dogancaglar.paymentservice.domain.exception.PspTransientException
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentMethod
import com.dogancaglar.paymentservice.ports.outbound.PspAuthorizationGatewayPort
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

@Component
@ConditionalOnProperty(name = ["psp.gateway.type"], havingValue = "SIMULATED")
class SimulatedPspAuthorizationGatewayAdapter(
    private val simulator: AuthorizationNetworkSimulator,
    private val config: AuthorizationSimulationProperties,
    @param:Qualifier("createPaymentIntentExecutor") private val createPaymentIntentExecutor: ThreadPoolTaskExecutor,
    @param:Qualifier("authorizePaymentIntentExecutor") private val authorizePaymentIntentExecutor: ThreadPoolTaskExecutor

) : PspAuthorizationGatewayPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val active: AuthorizationSimulationProperties.ScenarioConfig
        get() = config.scenarios[config.scenario]
            ?: throw IllegalStateException("No scenario config for ${config.scenario}")

    @WithSpan("SimulatedCreatePaymentIntent")
    override fun createPaymentIntent(paymentIntent: PaymentIntent): CompletableFuture<PaymentIntent> {
        return CompletableFuture.supplyAsync({
            simulator.simulate()
            val sc = active.response
            val roll = Random.nextInt(100)

            when {
                roll < sc.successful -> {
                    paymentIntent.markAsCreatedWithPspReferenceAndClientSecret(
                        pspReference = "sim_pi_${UUID.randomUUID()}",
                        clientSecret = "sim_cs_${UUID.randomUUID()}"
                    )
                }
                roll < sc.successful + sc.retryable -> {
                    throw PspTransientException("Simulated transient PSP failure", RuntimeException("transient simulator"))
                }
                else -> {
                    throw PspPermanentException("Simulated permanent PSP failure", RuntimeException("permanent simulator"))
                }
            }
        }, createPaymentIntentExecutor)
    }

    @WithSpan("SimulatedCreatePaymentIntent")
    override fun authorizePaymentIntent(paymentIntent: PaymentIntent, token: PaymentMethod?): CompletableFuture<PaymentIntent> {
        return CompletableFuture.supplyAsync({
            simulator.simulate()
            val sc = active.response
            val roll = Random.nextInt(100)

            when {
                roll < sc.successful -> {
                    paymentIntent.markAuthorized()
                }
                roll < sc.successful + sc.retryable -> {
                    throw PspTransientException("Simulated transient PSP failure", RuntimeException("transient simulator"))
                }
                else -> {
                    throw PspPermanentException("Simulated permanent PSP failure", RuntimeException("permanent simulator"))
                }
            }
        }, authorizePaymentIntentExecutor)
    }

    override fun retrieveClientSecret(pspReference: String): CompletableFuture<String>? {
        return CompletableFuture.supplyAsync({
            simulator.simulate()
            val sc = active.response
            val roll = Random.nextInt(100)

            when {
                roll < sc.successful -> {
                    "sim_cs_${UUID.randomUUID()}"
                }
                roll < sc.successful + sc.retryable -> {
                    throw PspTransientException("Simulated transient PSP failure", RuntimeException("transient simulator"))
                }
                else -> {
                    throw PspPermanentException("Simulated permanent PSP failure", RuntimeException("permanent simulator"))
                }
            }
        }, authorizePaymentIntentExecutor)
    }
}
