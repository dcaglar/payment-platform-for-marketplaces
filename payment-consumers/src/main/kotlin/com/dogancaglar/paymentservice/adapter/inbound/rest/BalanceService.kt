package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.domain.model.ledger.AccountProfile
import com.dogancaglar.paymentservice.domain.model.ledger.AccountType
import com.dogancaglar.paymentservice.ports.inbound.usecases.AccountBalanceReadUseCase
import com.dogancaglar.paymentservice.ports.outbound.AccountDirectoryPort
import com.dogancaglar.port.out.web.dto.AccountBalanceDto
import com.dogancaglar.port.out.web.dto.BalanceDto
import com.dogancaglar.port.out.web.dto.CurrencyEnum
import com.dogancaglar.port.out.web.dto.OwnerType
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service

/**
 * Reads balances for the balance API. Accounts are found in account_directory: a seller's account
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

    /** A seller's balance read by a merchant: only the merchant's own sellers. */
    fun getSellerBalanceForMerchant(sellerId: String, merchantId: String): BalanceDto {
        val profiles = sellerAccounts(sellerId)
        for (profile in profiles) {
            if (profile.masterAccountCode != merchantId) {
                throw AccessDeniedException("Seller $sellerId does not belong to merchant $merchantId")
            }
        }
        return toBalance(OwnerType.SELLER, sellerId, profiles)
    }

    /** A merchant's own balance: its direct-sales payable and its marketplace commission payable. */
    fun getMerchantBalance(merchantId: String): BalanceDto {
        val profiles = mutableListOf<AccountProfile>()
        profiles.addAll(accountDirectory.getAccountProfilesByMaster(AccountType.MERCHANT_DIRECT_PAYABLE, merchantId))
        profiles.addAll(accountDirectory.getAccountProfilesByMaster(AccountType.MERCHANT_COMMISSION_PAYABLE, merchantId))
        if (profiles.isEmpty()) {
            throw BalanceOwnerNotFoundException("No merchant accounts for $merchantId")
        }
        return toBalance(OwnerType.MERCHANT, merchantId, profiles)
    }

    private fun sellerAccounts(sellerId: String): List<AccountProfile> {
        val profiles = accountDirectory.getAccountProfilesBySubEntity(AccountType.SELLER_PAYABLE, sellerId)
        if (profiles.isEmpty()) {
            throw BalanceOwnerNotFoundException("No seller account for $sellerId")
        }
        return profiles
    }

    private fun toBalance(ownerType: OwnerType, ownerId: String, profiles: List<AccountProfile>): BalanceDto {
        val currency = profiles[0].currency
        for (profile in profiles) {
            if (profile.currency != currency) {
                // One response has one currency and one total; several currencies are not supported yet
                throw IllegalStateException("$ownerId has accounts in several currencies; expected one")
            }
        }

        val accounts = mutableListOf<AccountBalanceDto>()
        var total = 0L
        for (profile in profiles) {
            val balance = accountBalanceReadUseCase.getRealTimeBalance(profile.accountCode)
            accounts.add(AccountBalanceDto(profile.type.name, profile.accountCode, balance))
            total += balance
        }
        logger.debug("Balance of {} {}: {} {} over {} account(s)", ownerType, ownerId, total, currency.currencyCode, accounts.size)

        return BalanceDto(
            ownerType = ownerType,
            ownerId = ownerId,
            currency = CurrencyEnum.valueOf(currency.currencyCode),
            total = total,
            accounts = accounts
        )
    }
}

/** No seller or merchant account for the requested id (404). */
class BalanceOwnerNotFoundException(message: String) : RuntimeException(message)
