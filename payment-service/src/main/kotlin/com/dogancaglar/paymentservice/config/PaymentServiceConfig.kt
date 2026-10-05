package com.dogancaglar.paymentservice.config

import com.dogancaglar.paymentservice.application.service.AuthorizePaymentIntentService
import com.dogancaglar.paymentservice.application.service.CapturePaymentService
import com.dogancaglar.paymentservice.application.service.CreatePaymentIntentService
import com.dogancaglar.paymentservice.application.service.GetPaymentIntentService
import com.dogancaglar.paymentservice.application.service.IdempotencyService
import com.dogancaglar.paymentservice.application.service.UpdatePaymentIntentService
import com.dogancaglar.paymentservice.infra.adapter.outbound.serialization.OutboxEventEventFactory
import com.dogancaglar.paymentservice.ports.inbound.usecases.CreatePaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.inbound.usecases.GetPaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.outbound.HasherPort
import com.dogancaglar.paymentservice.ports.outbound.IdGeneratorPort
import com.dogancaglar.paymentservice.ports.outbound.IdempotencyStorePort
import com.dogancaglar.paymentservice.ports.outbound.LocalOutboxWriterPort
import com.dogancaglar.paymentservice.ports.outbound.OutboxEventFactoryPort
import com.dogancaglar.paymentservice.ports.outbound.PaymentIntentRepository
import com.dogancaglar.paymentservice.ports.outbound.PaymentTransactionalFacadePort
import com.dogancaglar.paymentservice.ports.outbound.PspAuthorizationGatewayPort
import com.dogancaglar.paymentservice.ports.outbound.ResilientExecutionPort
import com.dogancaglar.paymentservice.ports.outbound.SerializationPort
import com.stripe.StripeClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PaymentServiceConfig(val serializationPort: SerializationPort) {

    // Same condition as StripePspAuthorizationGatewayAdapter, the only user of the client
    @ConditionalOnProperty(name = ["psp.gateway.type"], havingValue = "STRIPE", matchIfMissing = true)
    @Bean
    fun stripeClient(
        @Value("\${stripe.api.api-key}") apiKey: String,
        @Value("\${stripe.api.connect-timeout:5000}") connectTimeout: Int,
        @Value("\${stripe.api.read-timeout:30000}") readTimeout: Int
    ): StripeClient {
        return StripeClient.StripeClientBuilder()
            .setApiKey(apiKey)
            .setConnectTimeout(connectTimeout)
            .setReadTimeout(readTimeout)
            .build()
    }

    @Bean
    fun capturePaymentService(
        @Qualifier("localOutboxWriterAdapter") localOutboxWriterPort: LocalOutboxWriterPort,
        paymentIntentRepository: PaymentIntentRepository,
        outboxEventFactoryPort: OutboxEventFactoryPort
    ): CapturePaymentService {
        return CapturePaymentService(
            localOutboxWriterPort,
            outboxEventFactoryPort,
            paymentIntentRepository
        )
    }

    @Bean
    fun authorizePaymentService(
        paymentIntentRepository: PaymentIntentRepository,
        resilientExecutionPort: ResilientExecutionPort,
        pspAuthGatewayPort: PspAuthorizationGatewayPort,
        paymentTransactionalFacadePort: PaymentTransactionalFacadePort,
        outboxEventFactoryPort: OutboxEventFactoryPort,
    ): AuthorizePaymentIntentService {
        return AuthorizePaymentIntentService(
            outboxEventFactoryPort = outboxEventFactoryPort,
            paymentIntentRepository = paymentIntentRepository,
            resilientExecutionPort = resilientExecutionPort,
            pspAuthGatewayPort = pspAuthGatewayPort,
            paymentTransactionalFacadePort = paymentTransactionalFacadePort
        )
    }

    @Bean
    fun updatePaymentIntentService(paymentIntentRepository: PaymentIntentRepository): UpdatePaymentIntentService {
        return UpdatePaymentIntentService(paymentIntentRepository)
    }

    @Bean
    fun createPaymentService(
        idGeneratorPort: IdGeneratorPort,
        paymentIntentRepository: PaymentIntentRepository,
        pspAuthGatewayPort: PspAuthorizationGatewayPort,
        resilientExecutionPort: ResilientExecutionPort
    ): CreatePaymentIntentUseCase {
        return CreatePaymentIntentService(
            paymentIntentRepository = paymentIntentRepository,
            idGeneratorPort = idGeneratorPort,
            pspAuthGatewayPort = pspAuthGatewayPort,
            resilientExecutionPort = resilientExecutionPort
        )
    }

    @Bean
    fun idempotencyService(
        store: IdempotencyStorePort,
        hasher: HasherPort,
        serializer: SerializationPort
    ): IdempotencyService {
        return IdempotencyService(store, hasher, serializer)
    }

    @Bean
    fun getPaymentIntentService(
        paymentIntentRepository: PaymentIntentRepository,
        pspAuthGatewayPort: PspAuthorizationGatewayPort,
        resilientExecutionPort: ResilientExecutionPort
    ): GetPaymentIntentUseCase {
        return GetPaymentIntentService(
            paymentIntentRepository = paymentIntentRepository,
            pspAuthGatewayPort = pspAuthGatewayPort,
            resilientExecutionPort = resilientExecutionPort
        )
    }

    @Bean
    fun outboxEventFactoryPort(
        serializationPort: SerializationPort,
        idGeneratorPort: IdGeneratorPort
    ): OutboxEventFactoryPort {
        return OutboxEventEventFactory(serializationPort, idGeneratorPort)
    }
}
