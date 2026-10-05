package com.dogancaglar.paymentservice.domain.model.payment

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.exception.PaymentIntentDomainException
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.require
import com.dogancaglar.paymentservice.domain.model.common.requireNotNull
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.OrderId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import java.time.LocalDateTime

/**
 * PaymentIntent
 *
 * Represents the shopper's intent to pay.
 * Drives the authorization workflow with the PSP, but does NOT model money movement.
 *
 * Lifecycle (simplified):
 *  CREATED -> PENDING_AUTH -> AUTHORIZED | DECLINED | CANCELLED
 */
class PaymentIntent
@Suppress("LongParameterList")
private constructor( // the object's own fields
    val paymentIntentId: PaymentIntentId,
    val clientSecret: String? = "",
    val pspReference: String?, // Stripe PaymentIntent id (nullable only before CREATED)
    val buyerId: BuyerId,
    val orderId: OrderId,
    val totalAmount: Amount,
    val processingModel: ProcessingModel,
    val merchantAccount: String,
    private val splitsDelegate: Lazy<List<PaymentSplit>>,
    val status: PaymentIntentStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val cardSummary: CardSummary? = null // brand + last 4, known once the PSP authorized (null if it did not say)
) {
    val splits: List<PaymentSplit> by splitsDelegate

    init {
        require(totalAmount.isPositive()) {
            PaymentIntentDomainException.InvariantViolationException(
                "paymentIntentId=${paymentIntentId.value}: totalAmount must be positive, but was " +
                    "${totalAmount.quantity}"
            )
        }

        // Domain invariants about PSP reference:
        when (status) {
            PaymentIntentStatus.CREATED_PENDING -> {
                require(pspReference == null) {
                    PaymentIntentDomainException.InvariantViolationException(
                        "paymentIntentId=${paymentIntentId.value}: pspReference must be null in CREATED_PENDING"
                    )
                }
            }
            PaymentIntentStatus.CREATED,
            PaymentIntentStatus.PENDING_AUTH,
            PaymentIntentStatus.AUTHORIZED,
            PaymentIntentStatus.DECLINED,
            -> {
                require(!pspReference.isNullOrBlank()) {
                    PaymentIntentDomainException.InvariantViolationException(
                        "paymentIntentId=${paymentIntentId.value}: pspReference is required in status=$status"
                    )
                }
            }

            else -> {}
        }
    }

    fun hasPspReference(): Boolean = !pspReference.isNullOrBlank()

    fun pspReferenceOrThrow(): String =
        requireNotNull(pspReference) {
            PaymentIntentDomainException.InvariantViolationException(
                "paymentIntentId=${paymentIntentId.value}: pspReference is not set"
            )
        }

    // ------------------------
    // AUTHORIZATION WORKFLOW
    // ------------------------

    /**
     * Transition from CREATED -> PENDING_AUTH.
     * Indicates that an authorization attempt has been initiated.
     */
    fun markAuthorizedPending(now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(status == PaymentIntentStatus.CREATED) {
            PaymentIntentDomainException.InvalidStateTransitionException(
                "paymentIntentId=${paymentIntentId.value}: can only start authorization from CREATED (current=$status)"
            )
        }
        return copy(status = PaymentIntentStatus.PENDING_AUTH, updatedAt = now)
    }

    fun markAsCreated(now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(status == PaymentIntentStatus.CREATED_PENDING) {
            PaymentIntentDomainException.InvalidStateTransitionException(
                "paymentIntentId=${paymentIntentId.value}: can only mark CREATED from CREATED_PENDING (current=$status)"
            )
        }
        return copy(status = PaymentIntentStatus.CREATED, updatedAt = now)
    }

    /**
     *      * CREATED_PENDING -> CREATED (must provide PSP reference,client secret,seecret never persisted)
     */
    fun markAsCreatedWithPspReferenceAndClientSecret(
        pspReference: String,
        clientSecret: String,
        now: LocalDateTime = Utc.nowLocalDateTime()
    ): PaymentIntent {
        require(status == PaymentIntentStatus.CREATED_PENDING) {
            PaymentIntentDomainException.InvalidStateTransitionException(
                "paymentIntentId=${paymentIntentId.value}: can only mark CREATED from CREATED_PENDING (current=$status)"
            )
        }
        require(pspReference.isNotBlank()) {
            PaymentIntentDomainException.InvariantViolationException(
                "paymentIntentId=${paymentIntentId.value}: pspReference must not be blank"
            )
        }
        // Note: clientSecret is only set in-memory for response, never persisted
        return copy(
            status = PaymentIntentStatus.CREATED,
            updatedAt = now,
            pspReference = pspReference,
            clientSecret = clientSecret
        )
    }

    /**
     * Apply a successful authorization result from the PSP, with the card it was paid with when the PSP said
     * (brand + last 4 only).
     * PENDING_AUTH -> AUTHORIZED
     */
    fun markAuthorized(cardSummary: CardSummary? = null, now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(status == PaymentIntentStatus.PENDING_AUTH) {
            PaymentIntentDomainException.InvalidStateTransitionException(
                "paymentIntentId=${paymentIntentId.value}: can only mark AUTHORIZED from PENDING_AUTH (current=$status)"
            )
        }
        return copy(status = PaymentIntentStatus.AUTHORIZED, updatedAt = now, cardSummary = cardSummary)
    }

    /**
     * The PSP had a temporary problem, or we got no usable answer: authorizing again is safe
     * (the PSP call carries the same idempotency key, so the PSP authorizes at most once).
     * Not used for a decline or a refusal: DECLINED and FAILED are final.
     * PENDING_AUTH -> CREATED
     */
    fun revertToCreated(now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(status == PaymentIntentStatus.PENDING_AUTH) {
            PaymentIntentDomainException.InvalidStateTransitionException(
                "paymentIntentId=${paymentIntentId.value}: can only revert to CREATED from PENDING_AUTH " +
                    "(current=$status)"
            )
        }
        return copy(status = PaymentIntentStatus.CREATED, updatedAt = now)
    }

    /**
     * Apply a declined authorization result from the PSP.
     * PENDING_AUTH -> DECLINED
     */
    fun markDeclined(now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(status == PaymentIntentStatus.PENDING_AUTH) {
            PaymentIntentDomainException.InvalidStateTransitionException(
                "paymentIntentId=${paymentIntentId.value}: can only mark DECLINED from PENDING_AUTH (current=$status)"
            )
        }
        return copy(status = PaymentIntentStatus.DECLINED, updatedAt = now)
    }

    /**
     * The PSP refused for good, nothing was charged and nothing will retry it:
     * - creating it (the PSP refused it, or the background call failed after we answered 202)
     *   CREATED_PENDING -> FAILED
     * - authorizing it (the PSP refused our request; a card decline is DECLINED, not FAILED)
     *   PENDING_AUTH -> FAILED
     */
    fun markFailed(now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(status == PaymentIntentStatus.CREATED_PENDING || status == PaymentIntentStatus.PENDING_AUTH) {
            PaymentIntentDomainException.InvalidStateTransitionException(
                "paymentIntentId=${paymentIntentId.value}: can only mark FAILED from CREATED_PENDING or PENDING_AUTH " +
                    "(current=$status)"
            )
        }
        return copy(status = PaymentIntentStatus.FAILED, updatedAt = now)
    }

    /**
     * Cancel the intent before authorization is completed.
     * Allowed from CREATED or PENDING_AUTH.
     */
    fun markCancelled(now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(
            status == PaymentIntentStatus.CREATED ||
                status == PaymentIntentStatus.PENDING_AUTH
        ) {
            PaymentIntentDomainException.InvalidStateTransitionException(
                "paymentIntentId=${paymentIntentId.value}: can only cancel from CREATED or PENDING_AUTH " +
                    "(current=$status)"
            )
        }

        return copy(status = PaymentIntentStatus.CANCELLED, updatedAt = now)
    }

    /**
     * Update clientSecret (used when retrieving from Stripe during polling)
     * Preserves existing pspReference (must already be set for statuses that require it)
     */
    fun withClientSecret(clientSecret: String): PaymentIntent {
        return copy(pspReference = this.pspReference, clientSecret = clientSecret)
    }

    // ------------------------
    // INTERNAL COPY
    // ------------------------

    private fun copy(
        status: PaymentIntentStatus = this.status,
        updatedAt: LocalDateTime = Utc.nowLocalDateTime(),
        pspReference: String? = this.pspReference,
        clientSecret: String? = this.clientSecret,
        cardSummary: CardSummary? = this.cardSummary,
    ): PaymentIntent = PaymentIntent(
        paymentIntentId = paymentIntentId,
        pspReference = pspReference,
        clientSecret = clientSecret,
        buyerId = buyerId,
        orderId = orderId,
        processingModel = processingModel,
        merchantAccount = merchantAccount,
        totalAmount = totalAmount,
        splitsDelegate = splitsDelegate,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        cardSummary = cardSummary
    )

    // ------------------------
    // FACTORY METHODS
    // ------------------------

    override fun toString(): String {
        return "PaymentIntent(paymentIntentId=${paymentIntentId.value}, clientSecret=$clientSecret, " +
            "pspReference=$pspReference, buyerId=${buyerId.value}, orderId=${orderId.value}, " +
            "totalAmount=$totalAmount, paymentOrderLines=$splits, status=$status, " +
            "createdAt=$createdAt, updatedAt=$updatedAt)"
    }

    companion object {
        @Suppress("LongParameterList") // the object's own fields
        fun createNew(
            paymentIntentId: PaymentIntentId,
            buyerId: BuyerId,
            orderId: OrderId,
            processingModel: ProcessingModel,
            merchantAccount: String,
            totalAmount: Amount,
            splits: List<PaymentSplit>
        ): PaymentIntent {
            val now = Utc.nowLocalDateTime()

            // Same rule as Payment: only a MARKETPLACE payment has splits (a DIRECT_MERCHANT sale has none)
            if (processingModel == ProcessingModel.MARKETPLACE) {
                val id = "paymentIntentId=${paymentIntentId.value}"
                require(splits.isNotEmpty()) {
                    PaymentIntentDomainException.SplitValidationException(
                        "$id: MARKETPLACE PaymentIntent must have at least one payment line"
                    )
                }
                val lineCurrencies = splits.map { it.amount.currency }.distinct()
                require(lineCurrencies.size == 1 && lineCurrencies.first() == totalAmount.currency) {
                    PaymentIntentDomainException.SplitCurrencyMismatchException(
                        "$id: all payment lines must use the currency of totalAmount ${totalAmount.currency}"
                    )
                }
                val sum = splits.sumOf { it.amount.quantity }
                require(sum == totalAmount.quantity) {
                    PaymentIntentDomainException.SplitValidationException(
                        "$id: totalAmount ${totalAmount.quantity} must equal the sum of payment lines $sum"
                    )
                }
            }

            return PaymentIntent(
                paymentIntentId = paymentIntentId,
                pspReference = null,
                buyerId = buyerId,
                orderId = orderId,
                processingModel = processingModel,
                merchantAccount = merchantAccount,
                totalAmount = totalAmount,
                splitsDelegate = lazyOf(splits),
                status = PaymentIntentStatus.CREATED_PENDING,
                createdAt = now,
                updatedAt = now
            )
        }

        @Suppress("LongParameterList") // the object's own fields
        fun rehydrate(
            paymentIntentId: PaymentIntentId,
            pspReference: String? = "",
            buyerId: BuyerId,
            orderId: OrderId,
            totalAmount: Amount,
            merchantAccount: String,
            processingModel: ProcessingModel,
            splitsDelegate: Lazy<List<PaymentSplit>>,
            status: PaymentIntentStatus,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime,
            cardSummary: CardSummary? = null
        ): PaymentIntent = PaymentIntent(
            paymentIntentId = paymentIntentId,
            pspReference = pspReference,
            buyerId = buyerId,
            orderId = orderId,
            merchantAccount = merchantAccount,
            processingModel = processingModel,
            totalAmount = totalAmount,
            splitsDelegate = splitsDelegate,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt,
            cardSummary = cardSummary
        )
    }
}
