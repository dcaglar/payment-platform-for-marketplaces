package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.common.id.PublicIdFactory
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.AuthorizationRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CreatePaymentIntentRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CreatePaymentIntentResponseDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.mapper.PaymentRequestMapper
import com.dogancaglar.paymentservice.application.command.GetPaymentIntentCommand
import com.dogancaglar.paymentservice.application.command.ProcessPaymentIntentUpdateCommand
import com.dogancaglar.paymentservice.application.validator.PaymentValidator
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.ports.inbound.usecases.AuthorizePaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.inbound.usecases.CreatePaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.inbound.usecases.GetPaymentIntentUseCase
import com.dogancaglar.paymentservice.ports.inbound.usecases.UpdatePaymentIntentUseCase
import com.stripe.model.Event
import com.stripe.model.PaymentIntent
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class PaymentApiOrchestrator(
    private val authorizePaymentIntentUseCase: AuthorizePaymentIntentUseCase,
    private val createPaymentIntentUseCase: CreatePaymentIntentUseCase,
    private val getPaymentIntentUseCase: GetPaymentIntentUseCase,
    private val updatePaymentIntentUseCase: UpdatePaymentIntentUseCase,
    private val paymentValidator: PaymentValidator,
) {
    private val logger = LoggerFactory.getLogger(PaymentApiOrchestrator::class.java)

    @WithSpan("PaymentApiOrchestrator.createPaymentIntent")
    fun createPaymentIntent(request: CreatePaymentIntentRequestDTO): CreatePaymentIntentResponseDTO {
        logger.info("🔁 PaymentApiOrchestrator.createPaymentIntent started")
        paymentValidator.validate(request)
        val cmd = PaymentRequestMapper.toCreatePaymentIntentCommand(request)
        // only persist to db.
        logger.info("⚡ [Orchestrator] Calling createPaymentIntentUseCase.create for order: ${request.orderId}")
        val paymentIntent = createPaymentIntentUseCase.create(cmd)
        return PaymentRequestMapper.toPaymentResponseDto(paymentIntent)
    }

    @WithSpan("PaymentApiOrchestrator.authorizepaymentintent")
    fun authorizePayment(
        publicPaymentIntentId: String,
        request: AuthorizationRequestDTO,
        merchantAccount: String
    ): CreatePaymentIntentResponseDTO {
        logger.info(
            "🔁 PaymentApiOrchestrator.authorizePayment started for short publicPaymentIntentId $publicPaymentIntentId"
        )
        val cmd = PaymentRequestMapper.toAuthorizePaymentIntentCommand(publicPaymentIntentId, request, merchantAccount)
        logger.info(
            "⚡ [Orchestrator] Calling authorizePaymentIntentUseCase.authorize for internal long numeric ID: " +
                "${cmd.paymentIntentId}"
        )
        val paymentIntent = authorizePaymentIntentUseCase.authorize(cmd)
        return PaymentRequestMapper.toPaymentResponseDto(paymentIntent)
    }

    fun getPaymentIntent(publicPaymentIntentId: String, merchantAccount: String): CreatePaymentIntentResponseDTO {
        val internalId = PublicIdFactory.toInternalId(publicPaymentIntentId)
        val paymentIntentId = PaymentIntentId(internalId)
        val cmd = GetPaymentIntentCommand(paymentIntentId, merchantAccount)
        val paymentIntent = getPaymentIntentUseCase.getPaymentIntent(cmd)
        return PaymentRequestMapper.toPaymentResponseDto(paymentIntent)
    }

    fun processWebhook(event: Event) {
        val dataObjectDeserializer = event.dataObjectDeserializer
        val stripeObject = if (dataObjectDeserializer.`object`.isPresent) {
            dataObjectDeserializer.`object`.get()
        } else {
            logger.error("Failed to deserialize Stripe object for event: ${event.id}")
            return
        }

        when (event.type) {
            "payment_intent.created" -> {
                if (stripeObject is PaymentIntent) {
                    handlePaymentIntentCreated(event, stripeObject)
                }
            }
            else -> logger.debug("Unhandled event type: ${event.type}")
        }
    }

    private fun handlePaymentIntentCreated(event: Event, stripeIntent: PaymentIntent) {
        val metaId = stripeIntent.metadata["payment_intent_id"]
        if (metaId == null) {
            logger.warn("Metadata payment_intent_id missing for event ${event.id}")
            return
        }
        val id = metaId.toLongOrNull()
        if (id == null) {
            logger.error("Invalid payment_intent_id in metadata: $metaId")
            return
        }
        val paymentIntentId = PaymentIntentId(id)
        val cmd = ProcessPaymentIntentUpdateCommand(
            paymentIntentId = paymentIntentId,
            pspReference = stripeIntent.id,
            clientSecret = stripeIntent.clientSecret,
            status = PaymentIntentStatus.CREATED
        )
        updatePaymentIntentUseCase.processUpdate(cmd)
        logger.debug("Processed webhook payment_intent.created for ${paymentIntentId.value}")
    }
}
