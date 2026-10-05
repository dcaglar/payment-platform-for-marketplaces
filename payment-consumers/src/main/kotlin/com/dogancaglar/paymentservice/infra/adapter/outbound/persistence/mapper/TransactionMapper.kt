package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper

import com.dogancaglar.common.db.entity.TransactionEntity
import com.dogancaglar.common.db.entity.TransactionSplitEntity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.Instant

/** Writes the back office's `transactions`. SQL in resources/mapper/TransactionMapper.xml. */
@Mapper
interface TransactionMapper {

    /** 1 = inserted, 0 = this payment's transaction is already there. */
    fun insertIfAbsent(transaction: TransactionEntity): Int

    fun insertSplitIfAbsent(split: TransactionSplitEntity): Int

    /** 1 = set now, 0 = already set or no such transaction. */
    fun markCaptured(@Param("paymentId") paymentId: Long, @Param("capturedAt") capturedAt: Instant): Int

    /** 1 = set now, 0 = already set or no such transaction. */
    fun markSettled(@Param("paymentId") paymentId: Long, @Param("settledAt") settledAt: Instant): Int

    fun exists(@Param("paymentId") paymentId: Long): Boolean

    /** [merchantAccount] null = any merchant. */
    fun findById(
        @Param(
            "paymentId"
        ) paymentId: Long,
        @Param("merchantAccount") merchantAccount: String?
    ): TransactionEntity?

    fun findSplits(@Param("paymentId") paymentId: Long): List<TransactionSplitEntity>

    /**
     * Filter arguments: null = do not filter on it. [status] is AUTHORIZED, CAPTURED or SETTLED; [processingModel]
     * MARKETPLACE or DIRECT_MERCHANT.
     */
    fun findPage(
        @Param("merchantAccount") merchantAccount: String?,
        @Param("orderId") orderId: String?,
        @Param("paymentId") paymentId: Long?,
        @Param("sellerId") sellerId: String?,
        @Param("status") status: String?,
        @Param("authorizedFrom") authorizedFrom: Instant?,
        @Param("authorizedTo") authorizedTo: Instant?,
        @Param("processingModel") processingModel: String?,
        @Param("offset") offset: Int,
        @Param("limit") limit: Int
    ): List<TransactionEntity>

    fun count(
        @Param("merchantAccount") merchantAccount: String?,
        @Param("orderId") orderId: String?,
        @Param("paymentId") paymentId: Long?,
        @Param("sellerId") sellerId: String?,
        @Param("status") status: String?,
        @Param("authorizedFrom") authorizedFrom: Instant?,
        @Param("authorizedTo") authorizedTo: Instant?,
        @Param("processingModel") processingModel: String?
    ): Long
}
