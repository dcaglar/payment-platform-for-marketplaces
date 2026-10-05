package com.dogancaglar.paymentservice.adapter.inbound.rest.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull

/**
 * CreatePaymentIntentRequestDTO
 *
 * Inbound REST payload for creating a PaymentIntent. Replaces the legacy DTO
 * that carried e-commerce PaymentSplit / cart-item semantics.
 *
 * THREE-LAYER CHAIN for the split concept:
 *   PaymentSplitRequestDTO  (REST — see PaymentSplitRequestDTO.kt)
 *       ↓  PaymentRequestMapper.toPaymentSplitDto()
 *   PaymentSplitDto         (Application — see PaymentSplitDto.kt)
 *       ↓  PaymentSplitDto.toDomain()
 *   PaymentSplit            (Domain — see PaymentSplit.kt)
 *
 * Key constraints:
 *  - No order lines, cart items, or product references.
 *  - processingModel is mandatory and drives downstream routing logic.
 *  - merchantAccount identifies the primary merchant-of-record entity.
 *  - splits is nullable: omitted for DIRECT_MERCHANT, required for MARKETPLACE.
 *    Cross-field validation (splits present iff MARKETPLACE) is enforced in
 *    the use-case layer where full context is available.
 *
 * @param buyerId            Identifier of the purchasing party.
 * @param merchantAccount  Identifier of the primary merchant (MoR entity).
 * @param totalAmount        Total transaction amount in smallest currency unit.
 * @param processingModel    Routing model; determines whether splits are processed.
 * @param splits             Optional list of [PaymentSplitRequestDTO]; required for MARKETPLACE.
 */
data class CreatePaymentIntentRequestDTO(
    @field:NotBlank
    val buyerId: String,

    @field:NotBlank
    val merchantAccount: String,

    @field:NotNull
    val processingModel: ProcessingModelDto,

    @field:NotNull
    @field:Valid
    val totalAmount: AmountDto,
    @field:NotEmpty
    val orderId: String,
    @field:Valid
    val splits: List<PaymentSplitRequestDTO>? = null
)
