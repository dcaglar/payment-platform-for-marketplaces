package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.application.command.CreateAccountCommand
import com.dogancaglar.paymentservice.domain.model.account.Address
import com.dogancaglar.paymentservice.domain.model.account.PlatformFee
import com.dogancaglar.paymentservice.domain.model.common.Amount
import com.dogancaglar.paymentservice.domain.model.common.Currency
import com.dogancaglar.paymentservice.ports.inbound.usecases.RequestAccountCreationUseCase
import com.dogancaglar.port.out.web.dto.CreateAccountRequestDTO
import org.springframework.stereotype.Service

/** Maps the account request to its command and hands it to the use case, which queues it. */
@Service
class AccountApiOrchestrator(
    private val requestAccountCreationUseCase: RequestAccountCreationUseCase
) {
    fun requestCreation(request: CreateAccountRequestDTO) {
        requestAccountCreationUseCase.requestCreation(toCommand(request))
    }

    private fun toCommand(request: CreateAccountRequestDTO): CreateAccountCommand {
        val currency = Currency(request.currency)
        var fixedFee = Amount.zero(currency)
        if (request.platformFeeFixed > 0) {
            fixedFee = Amount.of(request.platformFeeFixed, currency)
        }
        return CreateAccountCommand(
            merchantAccountCode = request.merchantAccountCode,
            legalName = request.legalName,
            address = Address.of(
                request.address.line1,
                request.address.line2,
                request.address.city,
                request.address.postalCode,
                request.address.country
            ),
            industry = request.industry,
            currency = currency,
            platformFee = PlatformFee.of(fixedFee, request.platformFeeBps),
            isAutoCaptured = request.isAutoCaptured,
            isAutoSettled = request.isAutoSettled,
            sellerAccountCodes = request.sellerAccountCodes
        )
    }
}
