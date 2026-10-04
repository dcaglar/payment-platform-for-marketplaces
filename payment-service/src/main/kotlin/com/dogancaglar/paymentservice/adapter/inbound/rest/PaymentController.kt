package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.common.id.PublicIdFactory
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.AuthorizationRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CaptureRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CaptureResponseDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CreatePaymentIntentRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CreatePaymentIntentResponseDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.validation.ValidUuidV7
import com.dogancaglar.paymentservice.application.service.IdempotencyExecutionStatus
import com.dogancaglar.paymentservice.application.service.IdempotencyService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
    // permission, and the body's merchantAccount must be the token's merchant_id (403 otherwise, before anything is stored)
    @PreAuthorize("hasAuthority('payment:write') and #request.merchantAccount == principal.claims['merchant_id']")
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
        logger.debug("create payment answered status={} for {}", responseDTO.status, responseDTO.paymentIntentId)

        // The status follows from the payment's state, so a replay (from the stored answer) gets exactly
        // the same answer as the first request: FAILED 422, CREATED_PENDING 202 (poll), else 201.
        val response: ResponseEntity.BodyBuilder
        if (responseDTO.status == "FAILED") {
            response = ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        } else if (responseDTO.status == "CREATED_PENDING") {
            response = ResponseEntity.status(HttpStatus.ACCEPTED).header("Retry-After", "2")
        } else {
            response = ResponseEntity.status(HttpStatus.CREATED)
        }
        response.header("Location", "/api/v1/payments/${responseDTO.paymentIntentId}")
        if (result.status == IdempotencyExecutionStatus.REPLAYED) {
            response.header("Idempotent-Replayed", "true")
        }
        return response.body(responseDTO)
    }

    /**
     * Get payment intent status (for polling when payment is pending)
     * Checks if pspReference exists and retrieves clientSecret from Stripe if available
     */
    @GetMapping("/payments/{paymentIntentId}")
    @PreAuthorize("hasAuthority('payment:read')")
    fun getPaymentIntent(
        @PathVariable("paymentIntentId") publicPaymentIntentId: String,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<CreatePaymentIntentResponseDTO> {
        logger.debug("📥 Getting payment intent: {}", publicPaymentIntentId)
        // looked up together with the caller's merchant: another merchant's intent is a 404
        val dto = paymentApiOrchestrator.getPaymentIntent(publicPaymentIntentId, merchantOf(jwt))
        return ResponseEntity.status(HttpStatus.OK).body(dto)
    }

    @PostMapping("/payments/{paymentIntentId}/authorize")
    @PreAuthorize("hasAuthority('payment:write')")
    fun authorizePayment(
        @PathVariable("paymentIntentId") publicPaymentIntentId: String,
        @Valid @RequestBody request: AuthorizationRequestDTO,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<CreatePaymentIntentResponseDTO> {
        // looked up together with the caller's merchant: another merchant's intent is a 404
        val dto = paymentApiOrchestrator.authorizePayment(publicPaymentIntentId, request, merchantOf(jwt))

        logger.debug("authorize answered status={} for {}", dto.status, publicPaymentIntentId)
        // FAILED: the PSP refused for good, nothing charged (same answer as a refused create)
        if (dto.status == "FAILED") {
            return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY) // 422
                .body(dto)
        }
        // not decided yet (PSP slow or pending): the checkout polls the Location
        if (dto.status == "PENDING_AUTH") {
            return ResponseEntity
                .status(HttpStatus.ACCEPTED) // 202
                .header("Location", "/api/v1/payments/$publicPaymentIntentId")
                .header("Retry-After", "2")
                .body(dto)
        }
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

    /** The merchant the caller acts for. A token without merchant_id cannot act for any merchant. */
    private fun merchantOf(jwt: Jwt): String =
        jwt.getClaimAsString("merchant_id") ?: throw AccessDeniedException("Token has no merchant_id claim")
}
