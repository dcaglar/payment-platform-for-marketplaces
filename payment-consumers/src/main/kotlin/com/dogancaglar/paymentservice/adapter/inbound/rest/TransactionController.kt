package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.AmountDto
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CardDto
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.PageDto
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.TransactionDto
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.TransactionSplitDto
import com.dogancaglar.paymentservice.application.transaction.Transaction
import com.dogancaglar.paymentservice.application.transaction.TransactionFilter
import com.dogancaglar.paymentservice.domain.exception.PaymentDomainException
import com.dogancaglar.paymentservice.domain.model.payment.CardSummary
import com.dogancaglar.paymentservice.domain.model.payment.PaymentStatus
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.ports.inbound.usecases.TransactionUseCase
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * The back office's transactions (base URL .../api/v1/transactions), permission transaction:read. Each endpoint has
 * one kind of caller:
 *   - a merchant (claim merchant_id): /transactions/merchants/me…, always its own;
 *   - staff (merchant:all): /transactions/merchants/{merchantAccount}…, the merchant named in the path.
 * A payment id is always looked up together with its merchant: another merchant's payment is not found (404).
 * URL convention as in BalanceController.
 */
@RestController
@RequestMapping("/api/v1")
class TransactionController(
    private val transactionUseCase: TransactionUseCase
) {

    // --- merchant: always its own, from the token ---

    @PreAuthorize("hasAuthority('transaction:read') and principal.claims['merchant_id'] != null")
    @GetMapping("/transactions/merchants/me")
    fun findMyTransactions(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) orderId: String?,
        @RequestParam(required = false) paymentId: Long?,
        @RequestParam(required = false) sellerId: String?,
        @RequestParam(required = false) status: PaymentStatus?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) processingModel: ProcessingModel?,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<PageDto<TransactionDto>> {
        val filter = TransactionFilter(
            jwt.getClaimAsString("merchant_id"),
            orderId,
            paymentIdOrNull(paymentId),
            sellerId,
            status,
            from,
            to,
            processingModel
        )
        return ResponseEntity.ok(page(filter, page, size, "/api/v1/transactions/merchants/me/"))
    }

    @PreAuthorize("hasAuthority('transaction:read') and principal.claims['merchant_id'] != null")
    @GetMapping("/transactions/merchants/me/{paymentId}")
    fun getMyTransaction(
        @PathVariable paymentId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TransactionDto> {
        // looked up together with the merchant: another merchant's payment is not found
        val transaction = transactionUseCase.getTransaction(PaymentId(paymentId), jwt.getClaimAsString("merchant_id"))
            ?: throw PaymentDomainException.PaymentNotFoundException("paymentId=$paymentId")
        return ResponseEntity.ok(
            toDto(transaction, withDetails = true, detailPrefix = "/api/v1/transactions/merchants/me/")
        )
    }

    // --- staff: the merchant is named in the path ---

    @PreAuthorize("hasAuthority('transaction:read') and hasAuthority('merchant:all')")
    @GetMapping("/transactions/merchants/{merchantAccount}")
    fun findTransactionsOfMerchant(
        @PathVariable merchantAccount: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) orderId: String?,
        @RequestParam(required = false) paymentId: Long?,
        @RequestParam(required = false) sellerId: String?,
        @RequestParam(required = false) status: PaymentStatus?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) processingModel: ProcessingModel?
    ): ResponseEntity<PageDto<TransactionDto>> {
        val filter = TransactionFilter(
            merchantAccount,
            orderId,
            paymentIdOrNull(paymentId),
            sellerId,
            status,
            from,
            to,
            processingModel
        )
        return ResponseEntity.ok(page(filter, page, size, "/api/v1/transactions/merchants/$merchantAccount/"))
    }

    @PreAuthorize("hasAuthority('transaction:read') and hasAuthority('merchant:all')")
    @GetMapping("/transactions/merchants/{merchantAccount}/{paymentId}")
    fun getTransactionOfMerchant(
        @PathVariable merchantAccount: String,
        @PathVariable paymentId: Long
    ): ResponseEntity<TransactionDto> {
        val transaction = transactionUseCase.getTransaction(PaymentId(paymentId), merchantAccount)
            ?: throw PaymentDomainException.PaymentNotFoundException(
                "paymentId=$paymentId, merchantAccount=$merchantAccount"
            )
        return ResponseEntity.ok(
            toDto(transaction, withDetails = true, detailPrefix = "/api/v1/transactions/merchants/$merchantAccount/")
        )
    }

    private fun page(filter: TransactionFilter, page: Int, size: Int, detailPrefix: String): PageDto<TransactionDto> {
        val transactions = transactionUseCase.findTransactions(filter, page, size)
        val totalItems = transactionUseCase.countTransactions(filter)
        val items = mutableListOf<TransactionDto>()
        for (transaction in transactions) {
            items.add(toDto(transaction, withDetails = false, detailPrefix = detailPrefix))
        }
        return PageDto.of(items, page, size, totalItems)
    }

    private fun paymentIdOrNull(paymentId: Long?): PaymentId? {
        if (paymentId == null) {
            return null
        }
        return PaymentId(paymentId)
    }

    /** [detailPrefix]: where the same caller reads one transaction (the merchant's or the staff URL). */
    private fun toDto(transaction: Transaction, withDetails: Boolean, detailPrefix: String): TransactionDto {
        val paymentId = transaction.paymentId.value.toString()
        var splits: List<TransactionSplitDto>? = null
        if (withDetails) {
            val lines = mutableListOf<TransactionSplitDto>()
            for (split in transaction.splits) {
                lines.add(
                    TransactionSplitDto(
                        split.accountType.name,
                        split.account,
                        amount(split.amount.quantity, split.amount.currency.currencyCode)
                    )
                )
            }
            splits = lines
        }
        return TransactionDto(
            paymentId = paymentId,
            paymentIntentId = transaction.publicPaymentIntentId,
            orderId = transaction.orderId.value,
            merchantAccount = transaction.merchantAccount,
            totalAmount = amount(transaction.totalAmount.quantity, transaction.totalAmount.currency.currencyCode),
            status = transaction.status().name,
            authorizedAt = transaction.authorizedAt,
            capturedAt = transaction.capturedAt,
            settledAt = transaction.settledAt,
            detailUrl = detailPrefix + paymentId,
            card = cardOf(transaction.cardSummary),
            buyerId = if (withDetails) transaction.buyerId.value else null,
            pspReference = if (withDetails) transaction.pspReference else null,
            processingModel = transaction.processingModel.name,
            splits = splits
        )
    }

    private fun amount(quantity: Long, currency: String) = AmountDto(quantity, currency)

    private fun cardOf(cardSummary: CardSummary?): CardDto? {
        if (cardSummary == null) {
            return null
        }
        return CardDto(cardSummary.brand.name, cardSummary.last4)
    }
}
