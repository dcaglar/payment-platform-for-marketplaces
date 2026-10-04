package com.dogancaglar.paymentservice.ports.inbound.usecases

interface AccountBalanceReadUseCase {
    /**
     * Returns the current (eventually consistent) balance: snapshot + delta.
     */
    fun getRealTimeBalance(accountCode: String): Long

    /**
     * Returns a strong, fully merged balance by flushing Redis delta into DB first.
     */
    fun getStrongBalance(accountCode: String): Long
}
