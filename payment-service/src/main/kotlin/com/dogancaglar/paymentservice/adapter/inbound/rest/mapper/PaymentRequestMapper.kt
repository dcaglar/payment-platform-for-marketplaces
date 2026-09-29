package com.dogancaglar.paymentservice.adapter.inbound.rest.mapper

import com.dogancaglar.common.id.PublicIdFactory
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.AuthorizationRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CaptureRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CaptureResponseDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CreatePaymentIntentRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.PaymentMethodDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CreatePaymentIntentResponseDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.PaymentSplitRequestDTO
import com.dogancaglar.paymentservice.application.command.AuthorizePaymentIntentCommand
import com.dogancaglar.paymentservice.application.command.CapturePaymentCommand
import com.dogancaglar.paymentservice.application.command.CreatePaymentIntentCommand
import com.dogancaglar.paymentservice.application.util.toPublicPaymentIntentId
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.domain.model.payment.PaymentIntent
import com.dogancaglar.paymentservice.domain.model.payment.PaymentMethod
import com.dogancaglar.paymentservice.domain.model.payment.ProcessingModel
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import com.dogancaglar.paymentservice.domain.model.vo.BuyerId
import com.dogancaglar.paymentservice.domain.model.vo.OrderId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.domain.model.payment.PaymentSplit
import java.time.format.DateTimeFormatter

object PaymentRequestMapper {
    fun toCreatePaymentIntentCommand(dto: CreatePaymentIntentRequestDTO): CreatePaymentIntentCommand =
        CreatePaymentIntentCommand(
            orderId = OrderId(dto.orderId),
            buyerId = BuyerId(dto.buyerId),
            merchantAccount = dto.merchantAccount,
            processingModel = ProcessingModel.valueOf(dto.processingModel.name),
            totalAmount = Amount.of(dto.totalAmount.quantity, Currency(dto.totalAmount.currency.name)),
            paymentSplits = dto.splits?.map { split ->
                when (split) {
                    is PaymentSplitRequestDTO.BalanceAccount -> PaymentSplit.of(
                        accountType = AccountType.SELLER_PAYABLE,
                        account = split.account,
                        amount = Amount.of(split.amount.quantity, Currency(split.amount.currency.name))
                    )
                    is PaymentSplitRequestDTO.Commission -> PaymentSplit.of(
                        accountType = AccountType.MERCHANT_COMMISSION_PAYABLE,
                        account = dto.merchantAccount,
                        amount = Amount.of(split.amount.quantity, Currency(split.amount.currency.name))
                    )
                }
            } ?: emptyList()
        )

    fun toAuthorizePaymentIntentCommand(publicPaymentIntentId:String, dto: AuthorizationRequestDTO): AuthorizePaymentIntentCommand =
        AuthorizePaymentIntentCommand(
            paymentIntentId = PaymentIntentId(PublicIdFactory.toInternalId(publicPaymentIntentId)),
            paymentMethod = dto.paymentMethod?.let { toPaymentMethod(it) }
        )
    fun toCapturePaymentCommand(publicPaymentIntentId:String, dto: CaptureRequestDTO): CapturePaymentCommand =
        CapturePaymentCommand(
            paymentIntentId = PaymentIntentId(PublicIdFactory.toInternalId(publicPaymentIntentId)),
            merchantAccount = dto.merchantAccount ,
            amount = Amount.of(dto.amount.quantity, Currency(dto.amount.currency.name)))




    fun toPaymentResponseDto(paymentIntent: PaymentIntent): CreatePaymentIntentResponseDTO {
        return CreatePaymentIntentResponseDTO(
            paymentIntentId = paymentIntent.paymentIntentId.toPublicPaymentIntentId(),
            clientSecret =  paymentIntent.clientSecret,
            status = paymentIntent.status.name,
            buyerId = paymentIntent.buyerId.value,
            orderId = paymentIntent.orderId.value,
            totalAmount = AmountMapper.toDto(paymentIntent.totalAmount),
            createdAt = paymentIntent.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    fun toCaptureResponseDto(paymentIntent: PaymentIntent): CaptureResponseDTO {
        return CaptureResponseDTO(
            publicPaymentIntentId = paymentIntent.paymentIntentId.toPublicPaymentIntentId(),
            status = paymentIntent.status.name,
            createdAt = paymentIntent.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    fun toPaymentMethod(dto: PaymentMethodDTO): PaymentMethod =
        when (dto) {
            is PaymentMethodDTO.CardToken ->
                PaymentMethod.CardToken(
                    token = dto.token,
                    cvc = dto.cvc
                )
        }

    fun toPaymentMethodOrNull(dto: PaymentMethodDTO?): PaymentMethod? =
        dto?.let { toPaymentMethod(it) }



}