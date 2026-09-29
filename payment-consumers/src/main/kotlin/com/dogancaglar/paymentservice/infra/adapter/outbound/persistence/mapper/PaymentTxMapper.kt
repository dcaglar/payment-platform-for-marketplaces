package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.common.db.entity.PaymentTxEntity
import org.apache.ibatis.annotations.Mapper

/**
 * PaymentTxMapper
 *
 * MyBatis mapper interface for the central DB 'payment_tx' table.
 * All SQL is defined in:
 *   payment-consumers/src/main/resources/mapper/PaymentTxMapper.xml
 */
@Mapper
interface PaymentTxMapper {

    fun upsert(entity: PaymentTxEntity)

    fun findByPaymentId(paymentId: Long): List<PaymentTxEntity>
}
