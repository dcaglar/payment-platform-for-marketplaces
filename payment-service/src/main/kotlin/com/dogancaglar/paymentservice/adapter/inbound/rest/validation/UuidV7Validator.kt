package com.dogancaglar.paymentservice.adapter.inbound.rest.validation

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import java.util.UUID

class UuidV7Validator : ConstraintValidator<ValidUuidV7, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value.isNullOrBlank()) return false
        return try {
            UUID.fromString(value).version() == UUID_VERSION_7
        } catch (@Suppress("SwallowedException") e: IllegalArgumentException) { // not a UUID: invalid, a result
            false
        }
    }

    private companion object {
        const val UUID_VERSION_7 = 7
    }
}
