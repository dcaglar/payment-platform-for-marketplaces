package com.dogancaglar.paymentservice.adapter.inbound.rest// 2. In payment-service (Infrastructure layer)
import com.dogancaglar.paymentservice.application.service.IdempotencyResult
import com.dogancaglar.paymentservice.application.service.IdempotencyService
import com.dogancaglar.paymentservice.ports.outbound.HasherPort
import com.dogancaglar.paymentservice.ports.outbound.IdempotencyStorePort
import com.dogancaglar.paymentservice.ports.outbound.SerializationPort
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind

class TracedIdempotencyService(
    store: IdempotencyStorePort,
    hasher: HasherPort,
    serializer: SerializationPort,
    openTelemetry: OpenTelemetry
) : IdempotencyService(store, hasher, serializer) {

    private val tracer = openTelemetry.getTracer("payment-service")

    override fun <REQ : Any, RES : Any> run(
        key: java.util.UUID,
        requestBody: REQ,
        responseClass: Class<RES>,
        idExtractor: (RES) -> Long,
        block: () -> RES
    ): IdempotencyResult<RES> {
        // Create manual span
        val span = tracer.spanBuilder("IdempotencyService.run")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("idempotency.key", key.toString())
            .startSpan()

        try {
            span.makeCurrent().use {
                // Execute real application logic
                return super.run(key, requestBody, responseClass, idExtractor, block)
            }
        } finally {
            span.end()
        }
    }
}