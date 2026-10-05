package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.edge

import com.dogancaglar.common.db.entity.PaymentIntentEntity
import org.apache.ibatis.annotations.Mapper
import java.time.Instant

@Mapper
interface PaymentIntentMapper {
    // Removed @Select annotation to avoid duplicate mapping with XML
    fun getMaxPaymentIntentId(): Long?

    fun tryMarkPendingAuth(id: Long, now: Instant): Int
    fun updatePspReference(paymentIntentId: Long, pspReference: String, now: Instant): Int

    @Suppress("LongParameterList") // one @Param per updated column
    fun updatePaymentIntentWithPspResponse(
        paymentIntentId: Long,
        pspReference: String?,
        status: String,
        cardBrand: String?,
        cardLast4: String?,
        updatedAt: Instant
    ): Int

    // Add other CRUD methods as needed, e.g.:
    fun insert(paymentIntent: PaymentIntentEntity): Int
    fun findById(id: Long): PaymentIntentEntity?
    fun findByIdForMerchant(
        @org.apache.ibatis.annotations.Param(
            "id"
        ) id: Long,
        @org.apache.ibatis.annotations.Param("merchantAccount") merchantAccount: String
    ): PaymentIntentEntity?
    fun deleteById(id: Long): Int
}
