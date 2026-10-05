package com.dogancaglar.paymentservice.domain.exception

/** An account is missing or an account rule was violated → non-retryable (Kafka: DLQ; API: 404 / 400). */
sealed class AccountDomainException(message: String) : NonRetryableException(message) {

    /** No merchant account with this code, although a payment or event refers to it. */
    class MerchantAccountNotFoundException(message: String) : AccountDomainException(message)

    /** No seller account with this id (or not under this merchant). */
    class SellerAccountNotFoundException(message: String) : AccountDomainException(message)

    /**
     * An account rule was violated (blank name or address, invalid code or country, fee out of range, a seller
     * under another merchant). Account objects are built from POST /api/v1/accounts, so the API answers 400.
     */
    class InvariantViolationException(message: String) : AccountDomainException(message)
}
