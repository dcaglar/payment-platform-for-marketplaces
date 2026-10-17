package com.dogancaglar.common.db.entity

/** One row of the `merchants` table. [profile] is JSON: legal name, address, industry. */
data class MerchantEntity(
    val accountCode: String,
    val status: String,
    val currency: String,
    val isAutoCaptured: Boolean,
    val isAutoSettled: Boolean,
    val platformFeeFixed: Long,
    val platformFeeBps: Int,
    val profile: String
)
