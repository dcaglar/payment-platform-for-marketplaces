package com.dogancaglar.paymentservice.domain.model.common

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
inline fun require(value: Boolean, lazyException: () -> Throwable) {
    contract {
        returns() implies value
    }
    if (!value) {
        throw lazyException()
    }
}

@OptIn(ExperimentalContracts::class)
inline fun <T : Any> requireNotNull(value: T?, lazyException: () -> Throwable): T {
    contract {
        returns() implies (value != null)
    }
    return value ?: throw lazyException()
}
