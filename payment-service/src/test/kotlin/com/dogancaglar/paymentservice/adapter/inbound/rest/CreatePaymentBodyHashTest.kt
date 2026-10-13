package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.AmountDto
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CreatePaymentIntentRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.CurrencyEnum
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.PaymentSplitRequestDTO
import com.dogancaglar.paymentservice.adapter.inbound.rest.dto.ProcessingModelDto
import com.dogancaglar.paymentservice.infra.adapter.outbound.hash.CanonicalJsonHasher
import com.dogancaglar.paymentservice.infra.adapter.outbound.serialization.JacksonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The idempotency store keeps a hash of the create request next to the key. A client that retries after a deploy
 * must get its stored answer, not a 422 "same key, different body", so the hash of a given body must not change
 * between builds. The value below was recorded by the Jackson 2 code before the Jackson 3 upgrade.
 */
class CreatePaymentBodyHashTest {

    @Test
    fun `hash of the sample marketplace request is unchanged`() {
        val body = CreatePaymentIntentRequestDTO(
            buyerId = "BUYER-1450",
            merchantAccount = "MARKETPLACE-5",
            processingModel = ProcessingModelDto.MARKETPLACE,
            totalAmount = AmountDto(3000, CurrencyEnum.EUR),
            orderId = "ORDER-1450",
            splits = listOf(
                PaymentSplitRequestDTO.BalanceAccount("SELLER-5-1", AmountDto(1320, CurrencyEnum.EUR)),
                PaymentSplitRequestDTO.Commission(AmountDto(180, CurrencyEnum.EUR)),
                PaymentSplitRequestDTO.BalanceAccount("SELLER-5-2", AmountDto(1320, CurrencyEnum.EUR)),
                PaymentSplitRequestDTO.Commission(AmountDto(180, CurrencyEnum.EUR))
            )
        )

        val hash = CanonicalJsonHasher(JacksonUtil.createObjectMapper()).hashBody(body)

        assertEquals("zUZIBhqUKX6wnrPb4ZfAxderleDqNSO7oYVTS0yF4zY=", hash)
    }
}
