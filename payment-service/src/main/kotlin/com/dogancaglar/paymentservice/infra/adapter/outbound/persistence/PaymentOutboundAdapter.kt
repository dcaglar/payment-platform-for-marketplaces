package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

/*
 * ═══════════════════════════════════════════════════════════════════
 *  MOVED — Epic 1 Architecture Correction
 * ═══════════════════════════════════════════════════════════════════
 *  PaymentOutboundAdapter has been moved to the payment-consumers module.
 *
 *  Reason: The Payment aggregate is a Central Core concern.
 *  It is created exclusively by PspResultConsumer in payment-consumers
 *  upon receiving a PaymentAuthorized Kafka event.
 *  The Edge Cell (payment-service) must NOT write to the payments table.
 *
 *  New location:
 *    payment-consumers/src/main/kotlin/
 *      .../infra/adapter/outbound/persistence/PaymentOutboundAdapter.kt
 * ═══════════════════════════════════════════════════════════════════
 */
