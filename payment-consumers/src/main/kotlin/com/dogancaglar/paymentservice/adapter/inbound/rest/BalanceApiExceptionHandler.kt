package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.common.time.Utc
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps the balance API's own errors to HTTP. Access errors (AccessDeniedException) are not handled
 * here: they go on to Spring Security, which answers 403 (see SecurityConfig).
 */
@RestControllerAdvice(assignableTypes = [BalanceController::class])
class BalanceApiExceptionHandler {

    @ExceptionHandler(BalanceOwnerNotFoundException::class)
    fun handleNotFound(ex: BalanceOwnerNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val body = mapOf(
            "timestamp" to Utc.nowInstant().toString(),
            "status" to 404,
            "error" to "Not Found",
            "code" to "NOT_FOUND",
            "message" to ex.message,
            "path" to request.requestURI
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }
}
