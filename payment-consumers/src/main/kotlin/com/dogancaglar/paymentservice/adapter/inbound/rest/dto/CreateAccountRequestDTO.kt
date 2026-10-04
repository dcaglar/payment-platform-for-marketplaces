package com.dogancaglar.paymentservice.adapter.inbound.rest.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * POST /api/v1/accounts: one merchant account with its sellers. The merchant's ledger accounts and each
 * seller's ledger account are created from it.
 */
data class CreateAccountRequestDTO(
    @field:NotBlank
    @field:Pattern(regexp = ACCOUNT_CODE)
    val merchantAccountCode: String,

    @field:NotBlank
    val legalName: String,

    @field:NotNull
    @field:Valid
    val address: AddressDTO,

    @field:NotBlank
    val industry: String,

    @field:NotNull
    @field:Pattern(regexp = "^[A-Z]{3}$")
    val currency: String,

    @field:NotNull
    @field:Min(0)
    val platformFeeFixed: Long,

    @field:NotNull
    @field:Min(0)
    //todo explain me what is thos
    @field:Max(10_000)
    val platformFeeBps: Int,

    val isAutoCaptured: Boolean = true,

    val isAutoSettled: Boolean = false,

    @field:NotNull
    val sellerAccountCodes: List<
        @NotBlank
        @Pattern(regexp = ACCOUNT_CODE)
        String
        >
)

data class AddressDTO(
    @field:NotBlank val line1: String,
    val line2: String? = null,
    @field:NotBlank val city: String,
    @field:NotBlank val postalCode: String,
    @field:NotBlank @field:Size(min = 2, max = 2) val country: String
)

/** Letters, digits, '-' and '_'; no '.', which separates the parts of a ledger account code. */
const val ACCOUNT_CODE = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$"
