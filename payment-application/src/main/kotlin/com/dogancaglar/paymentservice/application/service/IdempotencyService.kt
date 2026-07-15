package com.dogancaglar.paymentservice.application.service

import com.dogancaglar.paymentservice.domain.exception.PaymentIntentNotReadyException
import com.dogancaglar.paymentservice.ports.outbound.HasherPort
import com.dogancaglar.paymentservice.ports.outbound.IdempotencyStorePort
import com.dogancaglar.paymentservice.ports.outbound.InitialRequestStatus
import com.dogancaglar.paymentservice.ports.outbound.SerializationPort
import org.slf4j.LoggerFactory

open class IdempotencyService(
    private val store: IdempotencyStorePort,
    private val hasher: HasherPort,
    private val serializer: SerializationPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    open fun <REQ : Any, RES : Any> run(
        key: java.util.UUID,
        requestBody: REQ,
        responseClass: Class<RES>,
        idExtractor: (RES) -> Long, // Final Addition: Extract internal ID from response
        block: () -> RES
    ): IdempotencyResult<RES> {
        val hash = hasher.hashBody(requestBody)

        // 1. Try to "Lock" the request
        val isFirst = store.tryInsertPending(key, hash)

        if (isFirst) {
            logger.debug("🆕 [Idempotency] Scenario 1: First request for key=$key. Executing block...")
            try {
                // Execute the actual business logic (CreatePaymentIntent, etc.)
                val response = block()

                // Finalize the record
                val json = serializer.toJson(response)
                val internalId = idExtractor(response) // Extract ID using the provided lambda

                store.updateResponsePayload(key, json, internalId)

                return IdempotencyResult(response, IdempotencyExecutionStatus.CREATED)
            } catch (e: Exception) {
                // If it fails, remove the "Lock" so the client can try again
                store.deletePending(key)
                throw e
            }
        }

        // 2. Handle Retries
        logger.debug("Idempotency RETRY request for key=$key")
        val record = store.findByKey(key)
            ?: error("Inconsistent state: idempotency key exists but row missing")

        if (record.requestHash != hash) {
            throw IdempotencyConflictClientException("Request body mismatch for existing key")
        }

        if (record.status == InitialRequestStatus.PENDING) {
            throw PaymentIntentNotReadyException("Original request still processing. Please wait.")
        }

        // 3. Replay the Result
        logger.debug("🔁 [Idempotency] Scenario 3: Valid retry for key=$key. Replaying cached response.")
        val responseObj = serializer.fromJson(record.responsePayload!!, responseClass)
        return IdempotencyResult(responseObj, IdempotencyExecutionStatus.REPLAYED)
    }
}



class IdempotencyConflictClientException(msg: String) : RuntimeException(msg)

open class IdempotencyResult<RES>(
    val response: RES,
    val status: IdempotencyExecutionStatus
)

enum class IdempotencyExecutionStatus {
    CREATED,     // 201
    REPLAYED,    // 200
}

