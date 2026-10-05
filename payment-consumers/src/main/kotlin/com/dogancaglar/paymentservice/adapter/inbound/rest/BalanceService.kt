package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.AccountBalanceDto
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.BalanceDto
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CurrencyEnum
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.OwnerType
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.PageDto
import com.dogancaglar.paymentservice.domain.exception.AccountDomainException
import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.LedgerAccountType
import com.dogancaglar.paymentservice.ports.inbound.usecases.AccountBalanceReadUseCase
import com.dogancaglar.paymentservice.ports.outbound.AccountDirectoryPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Reads balances for the balance API. Accounts are found in `accounts` (via the ledger_account_directory view): a
 * seller's account
 * by its sub_entity_id, a merchant's accounts by master_account_code. Each balance is the real-time
 * value (snapshot + Redis delta).
 */
@Service
class BalanceService(
    private val accountBalanceReadUseCase: AccountBalanceReadUseCase,
    private val accountDirectory: AccountDirectoryPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** A seller's balance (the seller itself, or finance/admin). A seller id is unique platform-wide. */
    fun getSellerBalance(sellerId: String): BalanceDto {
        val profiles = sellerAccounts(sellerId)
        return toBalance(OwnerType.SELLER, sellerId, profiles)
    }

    /** A seller's balance read by a merchant: only its own sellers; another merchant's seller is not found (404). */
    fun getSellerBalanceForMerchant(sellerId: String, merchantId: String): BalanceDto {
        val own = mutableListOf<AccountProfile>()
        for (profile in accountDirectory.getAccountProfilesBySubEntity(LedgerAccountType.SELLER_PAYABLE, sellerId)) {
            if (profile.masterAccountCode == merchantId) {
                own.add(profile)
            }
        }
        if (own.isEmpty()) {
            throw AccountDomainException.SellerAccountNotFoundException("sellerId=$sellerId, merchantId=$merchantId")
        }
        return toBalance(OwnerType.SELLER, sellerId, own)
    }

    /** A merchant's own balance: its direct-sales payable and its marketplace commission payable. */
    fun getMerchantBalance(merchantId: String): BalanceDto {
        val profiles = mutableListOf<AccountProfile>()
        profiles.addAll(
            accountDirectory.getAccountProfilesByMaster(LedgerAccountType.MERCHANT_DIRECT_PAYABLE, merchantId)
        )
        profiles.addAll(
            accountDirectory.getAccountProfilesByMaster(LedgerAccountType.MERCHANT_COMMISSION_PAYABLE, merchantId)
        )
        if (profiles.isEmpty()) {
            throw AccountDomainException.MerchantAccountNotFoundException("merchantId=$merchantId")
        }
        return toBalance(OwnerType.MERCHANT, merchantId, profiles)
    }

    /** One page of a merchant's sellers, each with its balance. [page] starts at 0. */
    fun getSellerBalancesOfMerchant(merchantId: String, page: Int, size: Int): PageDto<BalanceDto> {
        require(page >= 0) { "page must be 0 or more" }
        require(size in 1..MAX_PAGE_SIZE) { "size must be between 1 and $MAX_PAGE_SIZE" }
        val sellers = accountDirectory.getSubEntityAccountProfilesByMaster(LedgerAccountType.SELLER_PAYABLE, merchantId)
        val items = mutableListOf<BalanceDto>()
        var i = page * size
        while (i < sellers.size && items.size < size) {
            val sellerId = sellers[i].subEntityId!!
            items.add(toBalance(OwnerType.SELLER, sellerId, listOf(sellers[i])))
            i++
        }
        return PageDto.of(items, page, size, sellers.size.toLong())
    }

    private fun sellerAccounts(sellerId: String): List<AccountProfile> {
        val profiles = accountDirectory.getAccountProfilesBySubEntity(LedgerAccountType.SELLER_PAYABLE, sellerId)
        if (profiles.isEmpty()) {
            throw AccountDomainException.SellerAccountNotFoundException("sellerId=$sellerId")
        }
        return profiles
    }

    private fun toBalance(ownerType: OwnerType, ownerId: String, profiles: List<AccountProfile>): BalanceDto {
        val currency = profiles[0].currency
        for (profile in profiles) {
            if (profile.currency != currency) {
                // One response has one currency and one total; several currencies are not supported yet
                error("$ownerId has accounts in several currencies; expected one")
            }
        }

        val accounts = mutableListOf<AccountBalanceDto>()
        var total = 0L
        for (profile in profiles) {
            val balance = accountBalanceReadUseCase.getRealTimeBalance(profile.accountCode)
            accounts.add(AccountBalanceDto(profile.type.name, profile.accountCode, balance))
            total += balance
        }
        logger.debug(
            "Balance of {} {}: {} {} over {} account(s)",
            ownerType,
            ownerId,
            total,
            currency.currencyCode,
            accounts.size
        )

        return BalanceDto(
            ownerType = ownerType,
            ownerId = ownerId,
            currency = CurrencyEnum.valueOf(currency.currencyCode),
            total = total,
            accounts = accounts
        )
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
