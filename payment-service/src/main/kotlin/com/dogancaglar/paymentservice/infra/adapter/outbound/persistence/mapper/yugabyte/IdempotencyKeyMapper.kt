package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.yugabyte

import com.dogancaglar.paymentservice.ports.outbound.IdempotencyRecord
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface IdempotencyKeyMapper {

    /** Returns number of inserted rows (1 for first request, 0 for duplicate) */
    fun insertPending(record: IdempotencyRecord): Long?

    fun findByKey(key: java.util.UUID): IdempotencyRecord?

    fun updatePaymentIntentId(
        @Param("key") key: java.util.UUID,
        @Param("paymentIntentId") paymentIntentId: Long
    )

    fun updateResponsePayload(
        @Param("key") key: java.util.UUID,
        @Param("payload") payload: String,
        @Param("paymentIntentId") paymentIntentId: Long
    )

    fun deletePending(key: java.util.UUID): Int
}
