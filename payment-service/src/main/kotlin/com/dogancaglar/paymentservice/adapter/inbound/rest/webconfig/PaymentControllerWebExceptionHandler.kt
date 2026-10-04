package com.dogancaglar.paymentservice.adapter.inbound.rest.webconfig

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.exception.IdempotencyKeyInProgressException
import com.dogancaglar.paymentservice.domain.exception.IdempotencyKeyReusedException
import com.dogancaglar.paymentservice.domain.exception.PaymentIntentNotFoundException
import com.dogancaglar.paymentservice.domain.exception.PaymentNotReadyException
import com.dogancaglar.paymentservice.domain.exception.PspInvalidPaymentException
import com.dogancaglar.paymentservice.domain.exception.PspTransientException
import com.dogancaglar.paymentservice.domain.exception.PspUnknownException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * The single place that maps our exceptions to HTTP and logs them (once).
 * The `code` tells the client what to do next:
 *
 * | code           | HTTP | client                                   |
 * |----------------|------|------------------------------------------|
 * | INVALID_REQUEST| 400  | stop, fix the request                    |
 * | FORBIDDEN      | 403  | stop                                     |
 * | NOT_FOUND      | 404  | stop                                     |
 * | IN_PROGRESS    | 409  | wait Retry-After, retry with the same key |
 * | KEY_REUSED     | 422  | new key for a new request                |
 * | RETRY_LATER    | 503  | wait Retry-After, retry with the same key |
 * | INTERNAL_ERROR | 500  | stop, report the traceId                 |
 */
@RestControllerAdvice
class PaymentControllerWebExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    enum class ErrorCode { INVALID_REQUEST, FORBIDDEN, NOT_FOUND, IN_PROGRESS, KEY_REUSED, RETRY_LATER, INTERNAL_ERROR }

    data class ErrorResponse(
        val timestamp: String = Utc.nowInstant().toString(),
        val status: Int,
        val error: String,
        val code: ErrorCode,
        val message: String?,
        val path: String?,
        val traceId: String? = null
    )

    // --- Spring MVC's own exceptions (bad JSON, missing header, bean validation, 404/405/415 ...) ---

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val servletRequest = (request as ServletWebRequest).request
        val httpStatus = HttpStatus.valueOf(status.value())
        val code = if (httpStatus == HttpStatus.NOT_FOUND) {
            ErrorCode.NOT_FOUND
        } else if (httpStatus == HttpStatus.SERVICE_UNAVAILABLE) {
            ErrorCode.RETRY_LATER
        } else if (httpStatus.is4xxClientError) {
            ErrorCode.INVALID_REQUEST
        } else {
            ErrorCode.INTERNAL_ERROR
        }
        log.warn("{} at {}: {}", code, servletRequest.requestURI, ex.message)
        val errorBody = createBody(httpStatus, code, ex.message, servletRequest)
        return super.handleExceptionInternal(ex, errorBody, headers, status, request)
    }

    // --- 400: the client sent something we can't accept ---

    @ExceptionHandler(
        ConstraintViolationException::class,
        IllegalArgumentException::class,
        PspInvalidPaymentException::class
    )
    fun handleInvalidRequest(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("INVALID_REQUEST at {}: {}", request.requestURI, ex.message)
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, request, ex.message ?: "Invalid request")
    }

    // --- 403: @PreAuthorize failed (the security filter's 403 does not see errors thrown inside the controller) ---

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("FORBIDDEN at {}: {}", request.requestURI, ex.message)
        return respond(
            HttpStatus.FORBIDDEN,
            ErrorCode.FORBIDDEN,
            request,
            "Access denied. You do not have the required permissions."
        )
    }

    // --- 404 ---

    @ExceptionHandler(PaymentIntentNotFoundException::class)
    fun handleNotFound(ex: PaymentIntentNotFoundException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("NOT_FOUND at {}: {}", request.requestURI, ex.message)
        return respond(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, request, ex.message)
    }

    // --- 409: the same request is still being processed ---

    @ExceptionHandler(
        IdempotencyKeyInProgressException::class, // same key, request still running
        PaymentNotReadyException::class // authorize before the PSP created the intent
    )
    fun handleInProgress(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("IN_PROGRESS at {}: {}", request.requestURI, ex.message)
        return respond(HttpStatus.CONFLICT, ErrorCode.IN_PROGRESS, request, ex.message, retryAfter())
    }

    // --- 422: idempotency key reused with a different body ---

    @ExceptionHandler(IdempotencyKeyReusedException::class)
    fun handleKeyReused(ex: IdempotencyKeyReusedException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("KEY_REUSED at {}: {}", request.requestURI, ex.message)
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.KEY_REUSED, request, ex.message)
    }

    // --- 503: not done, might work next time ---

    @ExceptionHandler(
        PspTransientException::class, // PSP not reached / temporary PSP problem / our PSP pool full
        PspUnknownException::class, // no usable PSP answer: sending the same request again is safe (PSP idempotency)
        TransientDataAccessException::class, // lock, deadlock, query timeout, optimistic lock
        DataAccessResourceFailureException::class, // DB connection failed (also CannotGetJdbcConnectionException)
        CannotCreateTransactionException::class, // no connection to start a transaction
        TransactionTimedOutException::class // transaction rolled back after its timeout
    )
    fun handleRetryLater(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("RETRY_LATER at {}: {}", request.requestURI, causeSummary(ex))
        return respond(
            HttpStatus.SERVICE_UNAVAILABLE,
            ErrorCode.RETRY_LATER,
            request,
            "Service temporarily unavailable, please retry",
            retryAfter()
        )
    }

    // --- 500: our fault or unknown outcome ---
    // e.g. PspPermanentException (the PSP refused our request), DataIntegrityViolationException, bugs. The details stay in the log, the client gets the traceId.

    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.error("INTERNAL_ERROR at {}", request.requestURI, ex)
        return respond(
            HttpStatus.INTERNAL_SERVER_ERROR,
            ErrorCode.INTERNAL_ERROR,
            request,
            "An unexpected error occurred"
        )
    }

    // --- Helpers ---

    private fun retryAfter(): HttpHeaders {
        val headers = HttpHeaders()
        headers.add(HttpHeaders.RETRY_AFTER, "2")
        return headers
    }

    private fun respond(
        status: HttpStatus,
        code: ErrorCode,
        request: HttpServletRequest,
        msg: String?,
        headers: HttpHeaders? = null
    ): ResponseEntity<ErrorResponse> {
        return ResponseEntity(createBody(status, code, msg, request), headers, status)
    }

    private fun createBody(status: HttpStatus, code: ErrorCode, msg: String?, request: HttpServletRequest) = ErrorResponse(
        status = status.value(),
        error = status.reasonPhrase,
        code = code,
        message = msg,
        path = request.requestURI,
        traceId = MDC.get("traceId") ?: request.getHeader("X-Trace-Id")
    )

    /** "Type: message -> CauseType: message", at most 5 levels, without the stack trace. */
    private fun causeSummary(ex: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = ex
        while (current != null && parts.size < 5) {
            parts.add("${current::class.simpleName}: ${current.message?.take(100)}")
            current = current.cause
        }
        return parts.joinToString(" -> ")
    }
}
