package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka.consumers

import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.kafka.metadata.ConsumerGroups
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.common.logging.EventLogContext
import com.dogancaglar.paymentservice.application.events.AccountCreationRequested
import com.dogancaglar.paymentservice.ports.inbound.usecases.CreateAccountUseCase
import com.dogancaglar.paymentservice.ports.outbound.EventDeduplicationPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Creates the accounts of each POST /api/v1/accounts request. Creation is idempotent: a merchant that already
 * exists is left as it is. A request that can never succeed (a seller code owned by another merchant, invalid
 * data) goes straight to the DLQ through the shared error handler instead of holding up the others; temporary
 * errors are retried first.
 */
@Component
class AccountCreationCommandExecutor(
    private val createAccountUseCase: CreateAccountUseCase,
    private val dedupe: EventDeduplicationPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [Topics.ACCOUNT_CREATION_REQUESTED],
        containerFactory = ConsumerGroups.ACCOUNT_CREATION_COMMAND_EXECUTOR + "-factory",
        groupId = ConsumerGroups.ACCOUNT_CREATION_COMMAND_EXECUTOR
    )
    fun consume(record: ConsumerRecord<String, EventEnvelope<AccountCreationRequested>>) {
        val envelope = record.value()
        EventLogContext.with(envelope) {
            val eventId = envelope.data.deterministicEventId()
            if (dedupe.exists(ConsumerGroups.ACCOUNT_CREATION_COMMAND_EXECUTOR, eventId)) {
                logger.warn("⚠️ Event is processed already, skipping eventId=$eventId")
                return@with
            }

            val request = envelope.data
            createAccountUseCase.create(request.toCommand())
            dedupe.markProcessed(ConsumerGroups.ACCOUNT_CREATION_COMMAND_EXECUTOR, eventId, 3600)
            logger.info("Account creation executed for merchant {}", request.merchantAccountCode)
        }
    }
}
