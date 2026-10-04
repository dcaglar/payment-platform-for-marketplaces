package com.dogancaglar.paymentservice.ports.outbound

import com.dogancaglar.paymentservice.domain.model.balance.AccountBalanceSnapshot

/**
 * Port for persisting account balance snapshots to database.
 */
interface AccountBalanceSnapshotPort {
    /**
     * Gets a snapshot for an account (or null if not exists).
     * @param accountCode The account code (e.g., "MERCHANT_PAYABLE.MERCHANT-456")
     */
    fun getSnapshot(accountCode: String): AccountBalanceSnapshot?

    /**
     * Saves or updates a snapshot.
     */
    fun saveSnapshot(snapshot: AccountBalanceSnapshot)

    /**
     * Finds all snapshots.
     */
    fun findAllSnapshots(): List<AccountBalanceSnapshot>

    /*
    find accountbalancesnapshots by accountcodes
     */
    fun findByAccountCodes(accountCodes: Set<String>): List<AccountBalanceSnapshot>
}
