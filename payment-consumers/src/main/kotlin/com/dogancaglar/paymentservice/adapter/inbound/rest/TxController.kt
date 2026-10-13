package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.common.id.PublicIdFactory
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.AmountDto
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.JournalEntryDto
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.PaymentDto
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.PostingDto
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.TxDto
import com.dogancaglar.paymentservice.domain.exception.LedgerDomainException
import com.dogancaglar.paymentservice.domain.exception.PaymentDomainException
import com.dogancaglar.paymentservice.domain.model.ledger.JournalEntry
import com.dogancaglar.paymentservice.domain.model.ledger.Posting
import com.dogancaglar.paymentservice.domain.model.ledger.Tx
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.TxId
import com.dogancaglar.paymentservice.ports.inbound.usecases.TxUseCase
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Txs and journal entries for finance (base URL .../api/v1/txs), permission ledger:read, staff only (merchant:all),
 * the merchant named in the path as in the other APIs. Ids are looked up together with that merchant: another
 * merchant's payment or tx is not found (404). Merchants never see txs or journal entries.
 */
@RestController
@RequestMapping("/api/v1/txs")
class TxController(
    private val txUseCase: TxUseCase
) {

    /** A payment's txs and all its journal entries, in recorded order. */
    @PreAuthorize("hasAuthority('ledger:read') and hasAuthority('merchant:all')")
    @GetMapping("/merchants/{merchantAccount}/payments/{paymentId}")
    fun getPayment(@PathVariable merchantAccount: String, @PathVariable paymentId: String): ResponseEntity<PaymentDto> {
        // the API speaks public ids (pay_…, tx_…); the numeric id stays inside
        val internalPaymentId = PaymentId(PublicIdFactory.toInternalId(paymentId))
        val txs = txUseCase.findTxsOfPayment(internalPaymentId, merchantAccount)
        if (txs.isEmpty()) {
            throw PaymentDomainException.PaymentNotFoundException(
                "paymentId=$paymentId, merchantAccount=$merchantAccount"
            )
        }
        val txDtos = mutableListOf<TxDto>()
        for (tx in txs) {
            txDtos.add(toDto(tx, merchantAccount, null))
        }
        val entryDtos = mutableListOf<JournalEntryDto>()
        for (entry in txUseCase.findJournalEntriesOfPayment(internalPaymentId, merchantAccount)) {
            entryDtos.add(toDto(entry))
        }
        return ResponseEntity.ok(PaymentDto(paymentId, merchantAccount, txDtos, entryDtos))
    }

    /** One tx with its journal entries. */
    @PreAuthorize("hasAuthority('ledger:read') and hasAuthority('merchant:all')")
    @GetMapping("/merchants/{merchantAccount}/{txId}")
    fun getTx(@PathVariable merchantAccount: String, @PathVariable txId: String): ResponseEntity<TxDto> {
        val internalTxId = TxId(PublicIdFactory.toInternalId(txId))
        val tx = txUseCase.getTx(internalTxId, merchantAccount)
            ?: throw LedgerDomainException.TxNotFoundException("txId=$txId, merchantAccount=$merchantAccount")
        val entryDtos = mutableListOf<JournalEntryDto>()
        for (entry in txUseCase.findJournalEntriesOfTx(internalTxId, merchantAccount)) {
            entryDtos.add(toDto(entry))
        }
        return ResponseEntity.ok(toDto(tx, merchantAccount, entryDtos))
    }

    private fun toDto(tx: Tx, merchantAccount: String, journalEntries: List<JournalEntryDto>?): TxDto {
        var acquirerReference: String? = null
        var parentTxId: Long? = null
        var settleStatus: String? = null
        when (tx) {
            is Tx.AuthorizationTx -> acquirerReference = tx.acquirerReference
            is Tx.CaptureTx -> {
                acquirerReference = tx.acquirerReference
                parentTxId = tx.authorizationTxId.value
                settleStatus = tx.settleStatus.name
            }
            is Tx.SettleTx -> {
                acquirerReference = tx.acquirerBatchReference
                parentTxId = tx.captureTxId.value
                settleStatus = tx.settleStatus.name
            }
            is Tx.RefundTx -> {
                acquirerReference = tx.acquirerReference
                parentTxId = tx.captureTxId.value
            }
            is Tx.PspFeeTx -> parentTxId = tx.parentTxId.value
            else -> {}
        }
        var publicParentTxId: String? = null
        if (parentTxId != null) {
            publicParentTxId = PublicIdFactory.publicTxId(parentTxId)
        }
        val publicTxId = PublicIdFactory.publicTxId(tx.txId.value)
        return TxDto(
            txId = publicTxId,
            txType = tx.txType.name,
            paymentId = PublicIdFactory.publicPaymentId(tx.paymentId.value),
            status = tx.status.name,
            amount = AmountDto(tx.amount.quantity, tx.amount.currency.currencyCode),
            acquirerReference = acquirerReference,
            parentTxId = publicParentTxId,
            settleStatus = settleStatus,
            createdAt = tx.createdAt,
            detailUrl = "/api/v1/txs/merchants/$merchantAccount/$publicTxId",
            journalEntries = journalEntries
        )
    }

    private fun toDto(entry: JournalEntry): JournalEntryDto {
        val postings = mutableListOf<PostingDto>()
        var debit = 0L
        var credit = 0L
        var currency = ""
        for (posting in entry.postings) {
            currency = posting.amount.currency.currencyCode
            var direction = "CREDIT"
            if (posting is Posting.Debit) {
                direction = "DEBIT"
                debit += posting.amount.quantity
            } else {
                credit += posting.amount.quantity
            }
            postings.add(
                PostingDto(
                    posting.account.accountCode,
                    posting.account.type.name,
                    direction,
                    AmountDto(posting.amount.quantity, currency)
                )
            )
        }
        val entryTxId = entry.txId
        var publicTxId: String? = null
        if (entryTxId != null) {
            publicTxId = PublicIdFactory.publicTxId(entryTxId.value)
        }
        return JournalEntryDto(
            id = entry.id,
            journalType = entry.journalType.name,
            name = entry.name,
            txId = publicTxId,
            postings = postings,
            totalDebit = AmountDto(debit, currency),
            totalCredit = AmountDto(credit, currency)
        )
    }
}
