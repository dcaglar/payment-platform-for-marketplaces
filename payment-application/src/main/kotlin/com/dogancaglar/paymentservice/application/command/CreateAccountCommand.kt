package com.dogancaglar.paymentservice.application.command

import com.dogancaglar.paymentservice.domain.model.account.Address
import com.dogancaglar.paymentservice.domain.model.account.PlatformFee
import com.dogancaglar.paymentservice.domain.model.common.Currency

/** Create one merchant account, its sellers, and all their ledger accounts. */
data class CreateAccountCommand(
    val merchantAccountCode: String,
    val legalName: String,
    val address: Address,
    val industry: String,
    val currency: Currency,
    val platformFee: PlatformFee,
    val isAutoCaptured: Boolean,
    val isAutoSettled: Boolean,
    val sellerAccountCodes: List<String>
) {
    init {
        val seen = HashSet<String>()
        for (code in sellerAccountCodes) {
            require(seen.add(code)) { "Seller account code $code appears more than once" }
        }
    }
}
