package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.AuthorizationRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CreatePaymentIntentRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CreatePaymentIntentResponseDTO
import com.dogancaglar.paymentservice.application.service.IdempotencyExecutionStatus
import com.dogancaglar.paymentservice.application.service.IdempotencyService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CaptureRequestDTO
import com.dogancaglar.common.id.PublicIdFactory
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CaptureResponseDTO

import org.springframework.validation.annotation.Validated
import com.dogancaglar.paymentservice.adapter.inbound.rest.validation.ValidUuidV7

@RestController
@RequestMapping("/api/v1")
@Validated
class PaymentController(
    private val paymentApiOrchestrator: PaymentApiOrchestrator,
    private val modificationOrchestrator: ModificationOrchestrator,
    private val idempotencyService: IdempotencyService,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

        /**
     * Create a new payment.
     *
     * Requires 'payment:write' authority.
     *
     * @param request Payment request containing order details and payment orders
     * @return ResponseEntity with 201,202,200 Created status and PaymentResponseDTO
     */
    @PostMapping("/payments")
    @PreAuthorize("hasAuthority('payment:write')")
    fun createPayment(
        @RequestHeader("Idempotency-Key") @ValidUuidV7 idempotencyKey: String,
        @Valid @RequestBody request: CreatePaymentIntentRequestDTO
    ): ResponseEntity<CreatePaymentIntentResponseDTO> {
        logger.debug("📥 Starting payment create intent reqeust")
        val result = idempotencyService.run(
            key = java.util.UUID.fromString(idempotencyKey),
            requestBody = request,
            responseClass = CreatePaymentIntentResponseDTO::class.java, // Arg 3: The type
            idExtractor = { response ->
                // Arg 4: The lambda to get the internal ID for DB storage
                PublicIdFactory.toInternalId(response.paymentIntentId!!)
            },
            block = {
                // Arg 5: The business logic block
                paymentApiOrchestrator.createPaymentIntent(request)
            }
        )

        val responseDTO = result.response
        // Return 201/200/202 Created with Location header (best practice for resource creation)
        logger.info("paymentintent ${responseDTO.paymentIntentId} is created successfully")
        return when (result.status) {

            IdempotencyExecutionStatus.CREATED -> {
                // Check if payment is pending (Stripe timed out)
                if (responseDTO.status == "CREATED_PENDING") {
                    ResponseEntity
                        .status(HttpStatus.ACCEPTED)  // 202
                        .header("Location", "/api/v1/payments/${responseDTO.paymentIntentId}")
                        .header("Retry-After", "2")
                        .body(responseDTO)  // clientSecret will be null/empty
                } else {
                    ResponseEntity
                        .status(HttpStatus.CREATED)  // 201
                        .header("Location", "/api/v1/payments/${responseDTO.paymentIntentId}")
                        .body(responseDTO)  // clientSecret should be present
                }
            }

            IdempotencyExecutionStatus.REPLAYED -> ResponseEntity
                .status(HttpStatus.OK)
                .header("Idempotent-Replayed", "true")
                .header("Location", "/api/v1/payments/${responseDTO.paymentIntentId}")
                .body(responseDTO)
        }
    }

    /**
     * Get payment intent status (for polling when payment is pending)
     * Checks if pspReference exists and retrieves clientSecret from Stripe if available
     */
    @GetMapping("/payments/{paymentIntentId}")
    @PreAuthorize("hasAuthority('payment:write')")
    fun getPaymentIntent(
        @PathVariable("paymentIntentId") publicPaymentIntentId: String
    ): ResponseEntity<CreatePaymentIntentResponseDTO> {
        logger.debug("📥 Getting payment intent: {}", publicPaymentIntentId)
        val dto = paymentApiOrchestrator.getPaymentIntent(publicPaymentIntentId)
        return ResponseEntity.status(HttpStatus.OK).body(dto)
    }

    @PostMapping("/payments/{paymentIntentId}/authorize")
    @PreAuthorize("hasAuthority('payment:write')")
    fun authorizePayment(
        @PathVariable("paymentIntentId") publicPaymentIntentId: String,
        @Valid @RequestBody request: AuthorizationRequestDTO
    ): ResponseEntity<CreatePaymentIntentResponseDTO> {

        val dto = paymentApiOrchestrator.authorizePayment(publicPaymentIntentId, request)

        logger.info("payment ${publicPaymentIntentId} is authorized successfully")
        return ResponseEntity
            .status(HttpStatus.OK)
            .body(dto)
    }


    @PostMapping("/payments/{paymentIntentId}/captures")
    @PreAuthorize("hasAuthority('payment:write')")
    fun capturePayment(
        @PathVariable("paymentIntentId") publicPaymentIntentId: String,
        @Valid @RequestBody request: CaptureRequestDTO
    ): ResponseEntity<CaptureResponseDTO> {
        logger.debug("📥 Received capture request for payment: $publicPaymentIntentId")
        val responseDTO = modificationOrchestrator.capturePayment(publicPaymentIntentId, request)


        return ResponseEntity.status(HttpStatus.OK).body(responseDTO)
    }
}