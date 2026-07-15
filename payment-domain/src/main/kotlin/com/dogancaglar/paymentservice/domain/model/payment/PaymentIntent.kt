package com.dogancaglar.paymentservice.domain.model.payment

import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.common.Amount
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
class PaymentIntent private constructor(
    val paymentIntentId: PaymentIntentId,
    val clientSecret: String? ="",
    val pspReference: String?,          // Stripe PaymentIntent id (nullable only before CREATED)
    val buyerId: BuyerId,
    val orderId: OrderId,
    val totalAmount: Amount,
    val processingModel: ProcessingModel,
    val merchantAccount: String,
    private val splitsDelegate: Lazy<List<PaymentSplit>>,
    val status: PaymentIntentStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    val splits: List<PaymentSplit> by splitsDelegate

    init {
        require(totalAmount.isPositive()) { "Total amount must be positive" }

        // Domain invariants about PSP reference:
        when (status) {
            PaymentIntentStatus.CREATED_PENDING -> {
                require(pspReference == null) {
                    "pspReference must be null in CREATED_PENDING"
                }
            }
            PaymentIntentStatus.CREATED,
            PaymentIntentStatus.PENDING_AUTH,
            PaymentIntentStatus.AUTHORIZED,
            PaymentIntentStatus.DECLINED,
             ->{
                require(!pspReference.isNullOrBlank()) {
                    "pspReference is required in status=$status"
                }
            }

            else -> {}
        }
    }

    fun hasPspReference(): Boolean = !pspReference.isNullOrBlank()

    fun pspReferenceOrThrow(): String =
        requireNotNull(pspReference) { "pspReference is not set for paymentIntentId=$paymentIntentId" }

    // ------------------------
    // AUTHORIZATION WORKFLOW
    // ------------------------

    /**
     * Transition from CREATED -> PENDING_AUTH.
     * Indicates that an authorization attempt has been initiated.
     */
    fun markAuthorizedPending(now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(status == PaymentIntentStatus.CREATED) {
            "Can only start authorization from CREATED (current=$status)"
        }
        return copy(status = PaymentIntentStatus.PENDING_AUTH, updatedAt = now)
    }


    fun markAsCreated(now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(status == PaymentIntentStatus.CREATED_PENDING) {
            "Can only start authorization from CREATED (current=$status)"
        }
        return copy(status = PaymentIntentStatus.CREATED, updatedAt = now)
    }


    /**
     *      * CREATED_PENDING -> CREATED (must provide PSP reference,client secret,seecret never persisted)
     */
    fun markAsCreatedWithPspReferenceAndClientSecret(pspReference: String, clientSecret: String, now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(status == PaymentIntentStatus.CREATED_PENDING) {
            "Can only mark CREATED from CREATED_PENDING (current=$status)"
        }
        require(pspReference.isNotBlank()) { "pspReference must not be blank" }
        // Note: clientSecret is only set in-memory for response, never persisted
        return copy(status = PaymentIntentStatus.CREATED, updatedAt = now, pspReference = pspReference, clientSecret = clientSecret)
    }

    /**
     * Apply a successful authorization result from the PSP.
     * PENDING_AUTH -> AUTHORIZED
     */
    fun markAuthorized(now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(status == PaymentIntentStatus.PENDING_AUTH) {
            "Can only mark AUTHORIZED from PENDING_AUTH (current=$status)"
        }
        return copy(status = PaymentIntentStatus.AUTHORIZED, updatedAt = now)
    }

    /**
     * Apply a declined authorization result from the PSP.
     * PENDING_AUTH -> DECLINED
     */
    fun markDeclined(now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(status == PaymentIntentStatus.PENDING_AUTH) {
            "Can only mark DECLINED from PENDING_AUTH (current=$status)"
        }
        return copy(status = PaymentIntentStatus.DECLINED, updatedAt = now)
    }

    /**
     * Cancel the intent before authorization is completed.
     * Allowed from CREATED or PENDING_AUTH.
     */
    fun markCancelled(now: LocalDateTime = Utc.nowLocalDateTime()): PaymentIntent {
        require(
            status == PaymentIntentStatus.CREATED ||
                    status == PaymentIntentStatus.PENDING_AUTH
        ) { "Can only cancel from CREATED or PENDING_AUTH (current=$status)" }

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
    ): PaymentIntent = PaymentIntent(
        paymentIntentId = paymentIntentId,
        pspReference = pspReference,
        clientSecret = clientSecret,
        buyerId = buyerId,
        orderId = orderId,
        processingModel =  processingModel,
        merchantAccount = merchantAccount,
        totalAmount = totalAmount,
        splitsDelegate = splitsDelegate,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    // ------------------------
    // FACTORY METHODS
    // ------------------------

    override fun toString(): String {
        return "PaymentIntent(paymentIntentId=${paymentIntentId.value}, clientSecret=$clientSecret, pspReference=$pspReference, buyerId=${buyerId.value}, orderId=${orderId.value}, totalAmount=$totalAmount, paymentOrderLines=$splits, status=$status, createdAt=$createdAt, updatedAt=$updatedAt)"
    }

    companion object {
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
            
            require(splits.isNotEmpty()) { "PaymentIntent must have at least one payment line" }
            val lineCurrencies = splits.map { it.amount.currency }.distinct()
            require(lineCurrencies.size == 1 && lineCurrencies.first() == totalAmount.currency) {
                "All payment lines must use the same currency as total amount"
            }
            val sum = splits.sumOf { it.amount.quantity }
            require(sum == totalAmount.quantity) {
                "Total amount (${totalAmount.quantity}) must equal sum of payment lines ($sum)"
            }

            return PaymentIntent(
                paymentIntentId = paymentIntentId,
                pspReference = null,
                buyerId = buyerId,
                orderId = orderId,
                processingModel =  processingModel,
                merchantAccount = merchantAccount,
                totalAmount = totalAmount,
                splitsDelegate = lazyOf(splits),
                status = PaymentIntentStatus.CREATED_PENDING,
                createdAt = now,
                updatedAt = now
            )
        }


        fun rehydrate(
            paymentIntentId: PaymentIntentId,
            pspReference: String? ="",
            buyerId: BuyerId,
            orderId: OrderId,
            totalAmount: Amount,
            merchantAccount: String,
            processingModel: ProcessingModel,
            splitsDelegate: Lazy<List<PaymentSplit>>,
            status: PaymentIntentStatus,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime
        ): PaymentIntent = PaymentIntent(
            paymentIntentId = paymentIntentId,
            pspReference =pspReference,
            buyerId = buyerId,
            orderId = orderId,
            merchantAccount = merchantAccount,
            processingModel = processingModel,
            totalAmount = totalAmount,
            splitsDelegate = splitsDelegate,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}