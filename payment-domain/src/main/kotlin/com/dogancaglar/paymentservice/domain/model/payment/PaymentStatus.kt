package com.dogancaglar.paymentservice.domain.model.payment

/**
 * PaymentStatus
 *
 * The complete state machine for the central Payment aggregate.
 * These states are enforced by the guard clauses in Payment's
 * mutation methods and are never allowed to transition backwards.
 *
 * State machine (happy path):
 *   AUTHORIZED → SENT_FOR_SETTLE → CAPTURED → SETTLED
 *
 * Partial capture path:
 *   AUTHORIZED → SENT_FOR_SETTLE → PARTIALLY_CAPTURED → CAPTURED
 *
 * RefundTx paths:
 *   CAPTURED → PARTIALLY_REFUNDED → REFUNDED
 *   PARTIALLY_CAPTURED → PARTIALLY_REFUNDED → REFUNDED
 *
 * Void path (no capture attempted):
 *   AUTHORIZED → VOIDED
 *
 * State definitions:
 *
 *  AUTHORIZED:
 *      The Payment aggregate has been created. The PSP has confirmed the
 *      authorization hold. No capture has been requested yet.
 *      This is the initial state set by [Payment.initializeFromAuthEvent].
 *
 *  SENT_FOR_SETTLE:
 *      An OutboxEvent<ExternalAsyncCaptureToPspPerformed> has been appended
 *      to the Central DB outbox, confirming the gateway accepted the capture
 *      API call (202 ACK). The CapturePspPerformedConsumer transitions the
 *      aggregate to this state. Money is in-flight; not yet confirmed by webhook.
 *
 *      GOLDEN RULE — NO GHOST STATES: The aggregate must NOT reach this state
 *      until the outbox acknowledgment is received. Transitioning here
 *      prematurely based on an optimistic HTTP call would constitute a Ghost State.
 *
 *  CAPTURED:
 *      All authorised funds have been captured and confirmed by the PSP webhook.
 *      capturedAmount == totalAmount. Ledger CAPTURE journals are applied.
 *
 *  PARTIALLY_CAPTURED:
 *      A subset of the authorized funds have been captured. capturedAmount > 0
 *      but capturedAmount < totalAmount.
 *
 *  VOIDED:
 *      The authorization was released without any capture being performed.
 *      Terminal state. capturedAmount == 0.
 *
 *  PARTIALLY_REFUNDED:
 *      Some captured funds have been refunded. refundedAmount > 0 but
 *      refundedAmount < capturedAmount.
 *
 *  REFUNDED:
 *      All captured funds have been refunded. refundedAmount == capturedAmount.
 *      Terminal state.
 */
enum class PaymentStatus {
    AUTHORIZED,
    SENT_FOR_SETTLE,
    CAPTURED,
    PARTIALLY_CAPTURED,
    VOIDED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    SETTLED
}
