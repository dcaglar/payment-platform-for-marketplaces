package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CaptureRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CaptureResponseDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.mapper.PaymentRequestMapper
import com.dogancaglar.paymentservice.ports.inbound.usecases.CapturePaymentUseCase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ModificationOrchestrator(
    private val capturePaymentUseCase: CapturePaymentUseCase
) {
    private val logger = LoggerFactory.getLogger(ModificationOrchestrator::class.java)

    fun capturePayment(publicPaymentIntentId: String, request: CaptureRequestDTO): CaptureResponseDTO {
        logger.info("🔁 ModificationOrchestrator.capturePayment started")
        val cmd = PaymentRequestMapper.toCapturePaymentCommand(publicPaymentIntentId, request)
        val paymentIntent = capturePaymentUseCase.capture(cmd)
        return PaymentRequestMapper.toCaptureResponseDto(paymentIntent)
    }
}
