package com.dogancaglar.paymentservice.adapter.inbound.rest.webconfig

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(
        ex: HttpMessageNotReadableException
    ): ResponseEntity<Map<String, String>> {
        logger.warn("Malformed JSON request: {}", ex.message)

        // Custom message for our specific polymorphic type failure requirement
        val errorMessage = if (ex.message?.contains("Could not resolve type id") == true || ex.message?.contains("Invalid type id") == true) {
            "Invalid type parameter provided for PaymentSplit. Supported types are: Commission, BalanceAccount."
        } else {
            "Malformed JSON request."
        }

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to "Bad Request", "message" to errorMessage))
    }
}
