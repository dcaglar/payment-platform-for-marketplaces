package com.dogancaglar.paymentservice.config

import com.dogancaglar.paymentservice.ports.outbound.*
import com.dogancaglar.paymentservice.application.service.AuthorizePaymentIntentService
import com.dogancaglar.paymentservice.application.service.CapturePaymentService
import com.dogancaglar.paymentservice.application.service.CreatePaymentIntentService
import com.dogancaglar.paymentservice.application.service.GetPaymentIntentService
import com.dogancaglar.paymentservice.application.service.UpdatePaymentIntentService
import com.dogancaglar.paymentservice.infra.adapter.outbound.serialization.OutboxEventEventFactory
import com.dogancaglar.paymentservice.ports.inbound.usecases.CreatePaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.inbound.usecases.GetPaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.outbound.PaymentTransactionalFacadePort
import com.dogancaglar.paymentservice.ports.outbound.PspAuthorizationGatewayPort
import com.dogancaglar.paymentservice.ports.outbound.ResilientExecutionPort
import com.stripe.StripeClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
class PaymentServiceConfig(val serializationPort: SerializationPort) {

    @Profile("test")
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
        @Qualifier("localOutboxWriterAdapter")  localOutboxWriterPort: LocalOutboxWriterPort,
        idGeneratorPort: IdGeneratorPort,
        paymentIntentRepository: PaymentIntentRepository,
        outboxEventFactoryPort: OutboxEventFactoryPort,
        serializationPort: SerializationPort): CapturePaymentService {
        return CapturePaymentService(localOutboxWriterPort, idGeneratorPort, outboxEventFactoryPort,serializationPort, paymentIntentRepository)
    }


    @Bean
    fun authorizePaymentService(
        idGeneratorPort: IdGeneratorPort,
        paymentIntentRepository: PaymentIntentRepository,
        resilientExecutionPort: ResilientExecutionPort,
        serializationPort: SerializationPort,
        pspAuthGatewayPort: PspAuthorizationGatewayPort,
        paymentTransactionalFacadePort : PaymentTransactionalFacadePort,
        outboxEventFactoryPort: OutboxEventFactoryPort,
    ): AuthorizePaymentIntentService {
        return AuthorizePaymentIntentService(
            idGeneratorPort = idGeneratorPort,
            outboxEventFactoryPort = outboxEventFactoryPort,
            paymentIntentRepository = paymentIntentRepository,
            resilientExecutionPort = resilientExecutionPort,
            pspAuthGatewayPort = pspAuthGatewayPort,
            serializationPort = serializationPort,
            paymentTransactionalFacadePort = paymentTransactionalFacadePort
        )
    }




    @Bean
    fun updatePaymentIntentService(paymentIntentRepository: PaymentIntentRepository): UpdatePaymentIntentService{
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
    fun getPaymentIntentService(
        paymentIntentRepository: PaymentIntentRepository,
        pspAuthGatewayPort: PspAuthorizationGatewayPort,
        resilientExecutionPort: ResilientExecutionPort
    ): GetPaymentIntentUseCase{
        return GetPaymentIntentService(
            paymentIntentRepository = paymentIntentRepository,
            pspAuthGatewayPort = pspAuthGatewayPort,
            resilientExecutionPort = resilientExecutionPort
            )
    }


    @Bean
    fun outboxEventFactoryPort(serializationPort: SerializationPort, idGeneratorPort: IdGeneratorPort): OutboxEventFactoryPort{
        return OutboxEventEventFactory(serializationPort, idGeneratorPort)
    }
}