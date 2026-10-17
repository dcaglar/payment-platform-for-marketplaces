package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import com.dogancaglar.common.db.converter.AccountEntityMapper
import com.dogancaglar.paymentservice.domain.model.account.MerchantAccount
import com.dogancaglar.paymentservice.domain.model.account.SellerAccount
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccount
import com.dogancaglar.paymentservice.infra.adapter.outbound.persistence.mapper.AccountMapper
import com.dogancaglar.paymentservice.ports.outbound.AccountCreationTransactionalFacadePort
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Repository
open class AccountCreationTransactionalFacadeAdapter(
    private val accountMapper: AccountMapper,
    @Qualifier("myObjectMapper") private val objectMapper: ObjectMapper
) : AccountCreationTransactionalFacadePort {

    @Transactional(timeout = 5)
    override fun createMerchantAccount(
        merchant: MerchantAccount,
        sellers: List<SellerAccount>,
        ledgerAccounts: List<LedgerAccount>
    ): Boolean {
        // The merchant decides: if it already exists, nothing is written
        val merchantRow = AccountEntityMapper.toEntity(merchant, profileJson(merchant))
        val inserted = accountMapper.insertMerchantIfAbsent(merchantRow)
        if (inserted == 0) {
            return false
        }

        // Sellers before ledger accounts: a seller's ledger account references the seller row.
        // A seller code taken by another merchant fails here (duplicate key) and rolls everything back.
        for (seller in sellers) {
            accountMapper.insertSeller(AccountEntityMapper.toEntity(seller))
        }
        for (ledgerAccount in ledgerAccounts) {
            accountMapper.insertLedgerAccount(AccountEntityMapper.toEntity(ledgerAccount))
        }
        return true
    }

    private fun profileJson(merchant: MerchantAccount): String {
        val address = LinkedHashMap<String, Any?>()
        address["line1"] = merchant.address.line1
        address["line2"] = merchant.address.line2
        address["city"] = merchant.address.city
        address["postalCode"] = merchant.address.postalCode
        address["country"] = merchant.address.country
        val profile = LinkedHashMap<String, Any?>()
        profile["legalName"] = merchant.legalName
        profile["address"] = address
        profile["industry"] = merchant.industry
        return objectMapper.writeValueAsString(profile)
    }
}
