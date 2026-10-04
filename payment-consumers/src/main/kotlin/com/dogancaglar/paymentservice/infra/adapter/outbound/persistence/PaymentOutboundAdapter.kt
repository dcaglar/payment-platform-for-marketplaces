package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.common.db.converter.PaymentEntityMapper
import com.dogancaglar.paymentservice.application.dto.PaymentSplitDto
import com.dogancaglar.paymentservice.domain.model.payment.Payment
import com.dogancaglar.paymentservice.domain.model.vo.PaymentId
import com.dogancaglar.paymentservice.domain.model.vo.PaymentIntentId
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.PaymentMapper
import com.dogancaglar.paymentservice.ports.outbound.PaymentRepository
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Repository

@Repository
class PaymentOutboundAdapter(
    private val paymentMapper: PaymentMapper,
    @Qualifier("myObjectMapper") private val objectMapper: ObjectMapper
) : PaymentRepository {

    private val splitsTypeRef = object : TypeReference<List<PaymentSplitDto>>() {}

    override fun save(payment: Payment): Payment {
        val splitsJson = objectMapper.writeValueAsString(payment.splits.map { PaymentSplitDto.fromDomain(it) })
        paymentMapper.insert(PaymentEntityMapper.toEntity(payment, splitsJson))
        return payment
    }

    override fun findById(paymentId: PaymentId): Payment {
        val entity = requireNotNull(paymentMapper.findById(paymentId.value)) {
            "Payment not found for paymentId=${paymentId.value}"
        }
        val splits = objectMapper.readValue(entity.splitsJson, splitsTypeRef).map { it.toDomain() }
        return PaymentEntityMapper.toDomain(entity, splits)
    }

    override fun findByPaymentIntentId(paymentIntentId: PaymentIntentId): Payment? {
        val entity = paymentMapper.findByPaymentIntentId(paymentIntentId.value) ?: return null
        val splits = objectMapper.readValue(entity.splitsJson, splitsTypeRef).map { it.toDomain() }
        return PaymentEntityMapper.toDomain(entity, splits)
    }

    override fun getMaxPaymentId(): PaymentId {
        val paymentIdLong = paymentMapper.getMaxPaymentId() ?: 0L
        return PaymentId(paymentIdLong)
    }

    override fun updatePayment(payment: Payment) {
        val splitsJson = objectMapper.writeValueAsString(payment.splits.map { PaymentSplitDto.fromDomain(it) })
        paymentMapper.update(PaymentEntityMapper.toEntity(payment, splitsJson))
    }
}
