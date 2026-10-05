package com.dogancaglar.paymentservice.domain.exception

/** An account the processing relies on is missing: our bug (or corrupt data) → non-retryable, DLQ. */
sealed class AccountDomainException(message: String) : NonRetryableException(message) {

    /** No merchant account with this code, although a payment or event refers to it. */
    class MerchantAccountNotFoundException(message: String) : AccountDomainException(message)

    /**
     * An account rule was violated (blank name or address, invalid code or country, fee out of range, a seller
     * under another merchant). Account objects are built from POST /api/v1/accounts, so the API answers 400.
     */
    class InvariantViolationException(message: String) : AccountDomainException(message)
}
