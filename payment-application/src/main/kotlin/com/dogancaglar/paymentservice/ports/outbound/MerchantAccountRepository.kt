package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.account.MerchantAccount

/** Reads merchants (who they are and how their payments are processed). */
interface MerchantAccountRepository {

    /** The merchant with this account code, or null if there is none. */
    fun findByCode(merchantAccount: String): MerchantAccount?
}
