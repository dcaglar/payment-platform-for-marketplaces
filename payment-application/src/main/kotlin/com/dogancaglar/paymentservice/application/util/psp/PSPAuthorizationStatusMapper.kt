package com.dogancaglar.paymentservice.application.util.psp

import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntentStatus

/**
 * Maps PSP authorization responses to internal PaymentIntentStatus values.
 * Used only in the synchronous authorization (web/API) flow.
 */
object PSPAuthorizationStatusMapper {

    fun fromPspAuthCode(code: String): PaymentIntentStatus = when (code.uppercase()) {
        // Typical PSP authorization outcomes
        "AUTHORIZED", "SUCCESS" -> PaymentIntentStatus.AUTHORIZED
        "DECLINED", "INSUFFICIENT_FUNDS", "CARD_EXPIRED" -> PaymentIntentStatus.DECLINED
        else -> PaymentIntentStatus.DECLINED // default defensive fallback
    }

    fun isAuthorized(code: String): Boolean {
        return fromPspAuthCode(code) == PaymentIntentStatus.AUTHORIZED
    }

    fun isDeclined(code: String): Boolean {
        return fromPspAuthCode(code) == PaymentIntentStatus.DECLINED
    }
}
