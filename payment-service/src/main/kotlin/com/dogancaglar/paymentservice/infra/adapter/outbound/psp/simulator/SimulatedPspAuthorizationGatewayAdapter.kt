package com.dogancaglar.paymentservice.infra.adapter.outbound.psp.simulator

import com.dogancaglar.paymentservice.domain.exception.PspOperation
import com.dogancaglar.paymentservice.domain.exception.PspPermanentException
import com.dogancaglar.paymentservice.domain.exception.PspTransientException
import com.dogancaglar.paymentservice.domain.model.payment.CardBrand
import com.dogancaglar.paymentservice.domain.model.payment.CardSummary
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentMethod
import com.dogancaglar.paymentservice.ports.outbound.PspAuthorizationGatewayPort
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import kotlin.random.Random

@Component
@ConditionalOnProperty(name = ["psp.gateway.type"], havingValue = "SIMULATED")
class SimulatedPspAuthorizationGatewayAdapter(
    private val simulator: AuthorizationNetworkSimulator,
    private val config: AuthorizationSimulationProperties,
    @param:Qualifier("createPaymentIntentExecutor") private val createPaymentIntentExecutor: ThreadPoolTaskExecutor,
    @param:Qualifier("authorizePaymentIntentExecutor") private val authorizePaymentIntentExecutor:
    ThreadPoolTaskExecutor

) : PspAuthorizationGatewayPort {

    private val active: AuthorizationSimulationProperties.ScenarioConfig
        get() = config.scenarios[config.scenario]
            ?: throw IllegalStateException("No scenario config for ${config.scenario}")

    @WithSpan("SimulatedCreatePaymentIntent")
    override fun createPaymentIntent(paymentIntent: PaymentIntent): CompletableFuture<PaymentIntent> {
        val op = PspOperation.CREATE_INTENT
        val id = paymentIntent.paymentIntentId.value
        return submit(createPaymentIntentExecutor, op, id) {
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
                    throw PspTransientException(op, id, "simulated transient failure")
                }
                else -> {
                    throw PspPermanentException(op, id, "simulated permanent failure")
                }
            }
        }
    }

    @WithSpan("SimulatedCreatePaymentIntent")
    override fun authorizePaymentIntent(
        paymentIntent: PaymentIntent,
        token: PaymentMethod?
    ): CompletableFuture<PaymentIntent> {
        val op = PspOperation.AUTHORIZE
        val id = paymentIntent.paymentIntentId.value
        return submit(authorizePaymentIntentExecutor, op, id) {
            simulator.simulate()
            val sc = active.response
            val roll = Random.nextInt(100)

            when {
                roll < sc.successful -> {
                    // the simulated card: Visa ending in 4242 (the usual test card)
                    paymentIntent.markAuthorized(CardSummary.of(CardBrand.VISA, "4242"))
                }
                roll < sc.successful + sc.retryable -> {
                    throw PspTransientException(op, id, "simulated transient failure")
                }
                else -> {
                    // a decline is a result, not an error
                    paymentIntent.markDeclined()
                }
            }
        }
    }

    override fun retrieveClientSecret(paymentIntent: PaymentIntent): CompletableFuture<String>? {
        val op = PspOperation.RETRIEVE_CLIENT_SECRET
        val id = paymentIntent.paymentIntentId.value
        return submit(authorizePaymentIntentExecutor, op, id) {
            simulator.simulate()
            val sc = active.response
            val roll = Random.nextInt(100)

            when {
                roll < sc.successful -> {
                    "sim_cs_${UUID.randomUUID()}"
                }
                roll < sc.successful + sc.retryable -> {
                    throw PspTransientException(op, id, "simulated transient failure")
                }
                else -> {
                    throw PspPermanentException(op, id, "simulated permanent failure")
                }
            }
        }
    }

    /**
     * Hands the PSP call to its thread pool. A full pool means the call was never sent:
     * not done, try again later.
     */
    private fun <T> submit(
        executor: ThreadPoolTaskExecutor,
        op: PspOperation,
        id: Long,
        task: () -> T
    ): CompletableFuture<T> {
        try {
            return CompletableFuture.supplyAsync({ task() }, executor)
        } catch (e: RejectedExecutionException) {
            throw PspTransientException(op, id, "not sent: thread pool is full", e)
        }
    }
}
