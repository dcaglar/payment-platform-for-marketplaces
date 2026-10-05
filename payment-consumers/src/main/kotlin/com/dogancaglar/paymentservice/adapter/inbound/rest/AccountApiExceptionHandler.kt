package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.exception.AccountDomainException
import com.dogancaglar.paymentservice.domain.exception.PaymentDomainException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps invalid account requests to 400: field errors (Bean Validation) and domain rules (codes, address,
 * fee, duplicate seller codes).
 */
@RestControllerAdvice(assignableTypes = [AccountController::class])
class AccountApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleInvalidFields(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest
    ): ResponseEntity<Map<String, Any?>> {
        val errors = mutableListOf<String>()
        for (fieldError in ex.bindingResult.fieldErrors) {
            errors.add("${fieldError.field}: ${fieldError.defaultMessage}")
        }
        return badRequest(errors.joinToString("; "), request)
    }

    // the account objects validate the request: their rule violations (and an invalid fee amount or currency) are
    // the caller's mistake; IllegalArgumentException is the application's own check (e.g. duplicate seller codes)
    @ExceptionHandler(
        AccountDomainException.InvariantViolationException::class,
        PaymentDomainException.InvalidAmountException::class,
        PaymentDomainException.InvalidCurrencyException::class,
        IllegalArgumentException::class
    )
    fun handleInvalidRequest(
        ex: RuntimeException,
        request: HttpServletRequest
    ): ResponseEntity<Map<String, Any?>> =
        badRequest(ex.message, request)

    private fun badRequest(message: String?, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val body = LinkedHashMap<String, Any?>()
        body["timestamp"] = Utc.nowInstant().toString()
        body["status"] = HttpStatus.BAD_REQUEST.value()
        body["error"] = "Bad Request"
        body["code"] = "VALIDATION_ERROR"
        body["message"] = message
        body["path"] = request.requestURI
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }
}
