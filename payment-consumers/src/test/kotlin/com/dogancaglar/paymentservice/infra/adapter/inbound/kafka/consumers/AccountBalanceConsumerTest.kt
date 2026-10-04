package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka.consumers

import com.dogancaglar.paymentservice.ports.inbound.usecases.AccountBalanceUseCase
import com.dogancaglar.paymentservice.ports.outbound.EventDeduplicationPort
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach

class AccountBalanceConsumerTest {

    private lateinit var accountBalanceService: AccountBalanceUseCase
    private lateinit var consumer: AccountBalanceConsumer
    private lateinit var eventDeduplicationPort: EventDeduplicationPort

    @BeforeEach
    fun setUp() {
        accountBalanceService = mockk(relaxed = true)
        eventDeduplicationPort = mockk(relaxed = true)

        consumer = AccountBalanceConsumer(accountBalanceService, eventDeduplicationPort)
    }
}
