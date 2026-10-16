package com.dogancaglar.common.db.entity

/** One row of the `sellers` table: a seller under its merchant. */
data class SellerEntity(
    val accountCode: String,
    val merchantCode: String,
    val status: String
)
