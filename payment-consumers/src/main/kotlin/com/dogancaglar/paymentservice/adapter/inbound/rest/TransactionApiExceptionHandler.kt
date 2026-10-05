package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.exception.PaymentDomainException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps the transaction API's own errors to HTTP. Access errors (AccessDeniedException) are not handled
 * here: they go on to Spring Security, which answers 403 (see SecurityConfig).
 */
@RestControllerAdvice(assignableTypes = [TransactionController::class])
class TransactionApiExceptionHandler {

    // no such payment for this merchant (another merchant's is "not found" too)
    @ExceptionHandler(PaymentDomainException.PaymentNotFoundException::class)
    fun handleNotFound(
        ex: PaymentDomainException.PaymentNotFoundException,
        request: HttpServletRequest
    ): ResponseEntity<Map<String, Any?>> {
        val body = mapOf(
            "timestamp" to Utc.nowInstant().toString(),
            "status" to HttpStatus.NOT_FOUND.value(),
            "error" to "Not Found",
            "code" to "NOT_FOUND",
            "message" to ex.message,
            "path" to request.requestURI
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }

    // e.g. page below 0 or size outside 1..100
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalid(ex: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val body = mapOf(
            "timestamp" to Utc.nowInstant().toString(),
            "status" to HttpStatus.BAD_REQUEST.value(),
            "error" to "Bad Request",
            "code" to "VALIDATION_ERROR",
            "message" to ex.message,
            "path" to request.requestURI
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }
}
