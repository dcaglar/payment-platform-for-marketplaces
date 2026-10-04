package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.common.db.entity.AccountBalanceEntity
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.model.balance.AccountBalanceSnapshot
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.AccountBalanceReadMapper
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.snapshotmapper.AccountBalanceWriteMapper
import com.dogancaglar.paymentservice.ports.outbound.AccountBalanceSnapshotPort
import org.springframework.stereotype.Repository

@Repository
class AccountBalanceSnapshotAdapter(
    private val readMapper: AccountBalanceReadMapper,
    private val writeMapper: AccountBalanceWriteMapper
) : AccountBalanceSnapshotPort {

    override fun getSnapshot(accountCode: String): AccountBalanceSnapshot? {
        val entity = readMapper.findByAccountCode(accountCode) ?: return null
        return toSnapshot(entity)
    }

    override fun saveSnapshot(snapshot: AccountBalanceSnapshot) {
        val entity = AccountBalanceEntity(
            accountCode = snapshot.accountCode,
            balance = snapshot.balance,
            lastAppliedEntryId = snapshot.lastAppliedEntryId,
            lastSnapshotAt = Utc.toInstant(snapshot.lastSnapshotAt),
            updatedAt = Utc.toInstant(snapshot.updatedAt)
        )
        writeMapper.insertOrUpdateSnapshot(entity)
    }

    override fun findAllSnapshots(): List<AccountBalanceSnapshot> {
        return readMapper.findAll()
            .map { toSnapshot(it) }
    }

    override fun findByAccountCodes(accountCodes: Set<String>): List<AccountBalanceSnapshot> {
        return readMapper.findByAccountCodes(accountCodes)
            .map { toSnapshot(it) }
            .toList()
    }

    private fun toSnapshot(entity: AccountBalanceEntity): AccountBalanceSnapshot {
        return AccountBalanceSnapshot(
            accountCode = entity.accountCode,
            balance = entity.balance,
            lastAppliedEntryId = entity.lastAppliedEntryId,
            lastSnapshotAt = Utc.fromInstant(entity.lastSnapshotAt),
            updatedAt = Utc.fromInstant(entity.updatedAt)
        )
    }
}
