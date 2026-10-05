// KafkaTypedConsumerFactoryConfig.kt
package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka

import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.kafka.metadata.ConsumerGroups
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.common.kafka.serde.EventEnvelopeKafkaSerializer
import com.dogancaglar.common.logging.GenericLogFields
import com.dogancaglar.common.time.Utc
import com.dogancaglar.paymentservice.domain.exception.NonRetryableException
import io.micrometer.observation.ObservationRegistry
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig.CLIENT_ID_CONFIG
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.convert.ConversionException
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.NonTransientDataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.RecordInterceptor
import org.springframework.kafka.listener.adapter.RecordFilterStrategy
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries
import org.springframework.kafka.support.KafkaHeaders.GROUP_ID
import org.springframework.kafka.support.serializer.DeserializationException
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ClassCastException
import java.lang.IllegalArgumentException
import java.lang.NullPointerException
import java.sql.SQLTransientException

@Configuration
class KafkaTypedConsumerFactoryConfig(
    private val bootKafkaProps: KafkaProperties,
    @Value("\${app.kafka.concurrency.journal-entries:3}") private val journalEntriesConcurrency: Int,
    @Value("\${app.kafka.concurrency.capture-commands:3}") private val captureCommandsConcurrency: Int,
    @Value("\${app.kafka.concurrency.capture-submitted:3}") private val captureSubmittedConcurrency: Int
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MAX_ERROR_MESSAGE_CHARS = 8_000 // DLQ header caps: no jumbo headers
        const val MAX_ERROR_STACKTRACE_CHARS = 16_000
        const val MAX_RETRIES = 5
        const val RETRY_INITIAL_INTERVAL_MS = 2_000L
        const val RETRY_MAX_INTERVAL_MS = 30_000L
        const val POLL_TIMEOUT_MS = 1000L
        const val IDLE_BETWEEN_POLLS_MS = 250L
        private const val HDR_VALUE_BYTES = "springDeserializerExceptionValue"
    }

    @Bean("custom-kafka-consumer-factory")
    fun defaultKafkaConsumerFactory(): DefaultKafkaConsumerFactory<String, EventEnvelope<*>> {
        val configs = bootKafkaProps.buildConsumerProperties().toMutableMap()
        return DefaultKafkaConsumerFactory<String, EventEnvelope<*>>(configs)
    }

    @Bean("dlqProducerFactory")
    fun dlqProducerFactory(): ProducerFactory<String, ByteArray> {
        val cfg = bootKafkaProps.buildProducerProperties().toMutableMap()
        cfg.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG)
        cfg[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG] = false
        cfg[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] =
            StringSerializer::class.java
        cfg[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] =
            ByteArraySerializer::class.java

        val factory = DefaultKafkaProducerFactory<String, ByteArray>(cfg)
        return factory
    }

    @Bean("dlqKafkaTemplate")
    fun dlqKafkaTemplate(
        @Qualifier("dlqProducerFactory") pf: ProducerFactory<String, ByteArray>,
        registry: ObservationRegistry
    ) = KafkaTemplate(pf).apply {
        setObservationEnabled(true)
        setObservationRegistry(registry)
    }

    @Bean
    @Suppress("LongMethod") // the error handler and its DLQ recoverer in one place
    fun errorHandler(
        @Qualifier("dlqKafkaTemplate") dlqTemplate: KafkaTemplate<String, ByteArray>,
        kafkaExponentialBackOff: ExponentialBackOffWithMaxRetries
    ): DefaultErrorHandler {
        val recoverer = ConsumerRecordRecoverer { rec, ex ->
            val src = rec.topic()
            val target = if (src.endsWith(".DLQ")) src else Topics.dlqOf(src)
            // the one log line per failed event (consumers don't log and rethrow): which event, which payment, why
            val envelope = rec.value() as? EventEnvelope<*>
            logger.error(
                "Event failed for good, sent to {}: eventType={}, eventId={}, aggregateId={}, " +
                    "topic={}, partition={}, offset={}",
                target,
                envelope?.eventType,
                envelope?.eventId,
                envelope?.aggregateId,
                src,
                rec.partition(),
                rec.offset(),
                ex
            )
            val key: String? = rec.key()?.toString()

            // Prefer original bytes captured by ErrorHandlingDeserializer
            val raw: ByteArray? = rec.headers().lastHeader(HDR_VALUE_BYTES)?.value()

            val valueBytes: ByteArray = raw
                ?: (rec.value() as? EventEnvelope<*>)?.let { env ->
                    EventEnvelopeKafkaSerializer().serialize(rec.topic(), env) ?: ByteArray(0)
                } ?: ByteArray(0)
            // Copy headers and add error diagnostics
            val headers = RecordHeaders(rec.headers().toArray()).apply {
                add("x-error-class", (ex?.javaClass?.name ?: "n/a").toByteArray())
                add(
                    "x-error-message",
                    (
                        (ex?.message ?: "")
                            .take(MAX_ERROR_MESSAGE_CHARS)
                        ).toByteArray()
                ) // cap to avoid jumbo headers
                add("x-error-stacktrace", stackTraceString(ex, MAX_ERROR_STACKTRACE_CHARS).toByteArray())
                add("x-recovered-at", Utc.nowInstant().toString().toByteArray())
                add(
                    "x-consumer-group",
                    (
                        rec.headers()
                            .lastHeader(GROUP_ID)?.let { String(it.value()) }
                            ?: "unknown"
                        ).toByteArray()
                )
            }

            val pr = ProducerRecord<String, ByteArray>(
                target,
                rec.partition(),
                null,
                key,
                valueBytes,
                headers
            )

            dlqTemplate.send(pr)
        }

        return DefaultErrorHandler(recoverer, kafkaExponentialBackOff).apply {
            setCommitRecovered(true)
            setAckAfterHandle(false)

            // keep your exception policy
            addRetryableExceptions(
                RetriableException::class.java,
                TransientDataAccessException::class.java,
                CannotAcquireLockException::class.java,
                SQLTransientException::class.java,
                CommitFailedException::class.java,
            )
            addNotRetryableExceptions(
                IllegalArgumentException::class.java,
                NullPointerException::class.java,
                ClassCastException::class.java,
                ConversionException::class.java,
                DeserializationException::class.java,
                SerializationException::class.java,
                MethodArgumentNotValidException::class.java,
                DuplicateKeyException::class.java,
                DataIntegrityViolationException::class.java,
                NonTransientDataAccessException::class.java,
                // ours: every NonRetryableException (invalid request, invariant, transition, ledger, PSP refusal)
                NonRetryableException::class.java,
            )
        }
    }

    private fun stackTraceString(ex: Throwable?, max: Int): String =
        if (ex == null) {
            ""
        } else {
            StringWriter().use { sw ->
                ex.printStackTrace(PrintWriter(sw))
                sw.toString().take(max)
            }
        }

    @Bean
    fun kafkaExponentialBackOff(): ExponentialBackOffWithMaxRetries =
        ExponentialBackOffWithMaxRetries(MAX_RETRIES).apply {
            initialInterval = RETRY_INITIAL_INTERVAL_MS
            multiplier = 2.0
            maxInterval = RETRY_MAX_INTERVAL_MS
        }

    @Suppress("LongParameterList") // one argument per consumer-group setting
    private fun <T : Event> createFactory(
        clientId: String,
        concurrency: Int,
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        consumerFactory: DefaultKafkaConsumerFactory<String, EventEnvelope<*>>,
        errorHandler: DefaultErrorHandler,
        expectedEventType: String? = null,
        ackDiscarded: Boolean = true,
        batchListener: Boolean = false
    ): ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<T>> =
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<T>>().apply {
            this.consumerFactory = consumerFactory
            containerProperties.clientId = clientId
            consumerFactory.updateConfigs(
                mapOf(CLIENT_ID_CONFIG to clientId)
            )
            containerProperties.pollTimeout = POLL_TIMEOUT_MS // block up to 1s waiting for data
            containerProperties.isMicrometerEnabled = false
            containerProperties.isObservationEnabled = false
            containerProperties.idleBetweenPolls = IDLE_BETWEEN_POLLS_MS // nap 250ms after an empty poll
            @Suppress("UNCHECKED_CAST")
            setRecordInterceptor(interceptor as RecordInterceptor<String, EventEnvelope<T>>)
            setCommonErrorHandler(errorHandler)
            setConcurrency(concurrency)
            setAutoStartup(true)
            isBatchListener = batchListener

            // enforce semantic type at the container level
            expectedEventType?.let {
                setRecordFilterStrategy(eventTypeFilter(expectedEventType))
                setAckDiscarded(ackDiscarded)
            }
        }

    @Bean(ConsumerGroups.PSP_RESULT_CONSUMER + "-factory")
    fun pspResultFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory")
        customFactory: DefaultKafkaConsumerFactory<String, EventEnvelope<*>>,
        errorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<Event>> {
        return createFactory(
            clientId = "psp-result-consumer",
            concurrency = 1,
            interceptor = interceptor,
            consumerFactory = customFactory,
            errorHandler = errorHandler,
            expectedEventType = null
        )
    }

    @Bean(ConsumerGroups.WEBHOOK_CAPTURE_CONFIRMED_PROCESSOR + "-factory")
    fun marketPlaceSplitConsumerFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory")
        customFactory: DefaultKafkaConsumerFactory<String, EventEnvelope<*>>,
        errorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<Event>> {
        return createFactory(
            clientId = "split-instruction-consumer-group",
            concurrency = 1,
            interceptor = interceptor,
            consumerFactory = customFactory,
            errorHandler = errorHandler,
            expectedEventType = null
        )
    }

    @Profile("test", "local", "azure")
    @Bean(ConsumerGroups.SETTLEMENT_RECORD_SIMULATOR + "-factory")
    fun settlementSimulatorFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory")
        customFactory: DefaultKafkaConsumerFactory<String, EventEnvelope<*>>,
        errorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<Event>> {
        return createFactory(
            clientId = ConsumerGroups.SETTLEMENT_RECORD_SIMULATOR,
            concurrency = 1,
            interceptor = interceptor,
            consumerFactory = customFactory,
            errorHandler = errorHandler,
            expectedEventType = null
        )
    }

    @Bean(ConsumerGroups.ACCOUNT_BALANCE_CONSUMER + "-factory")
    fun journalEntriesRecordedFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory")
        customFactory: DefaultKafkaConsumerFactory<String, EventEnvelope<*>>,
        errorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<Event>> {
        return createFactory(
            clientId = "journal-entries-consumer",
            concurrency = journalEntriesConcurrency,
            interceptor = interceptor,
            consumerFactory = customFactory,
            errorHandler = errorHandler,
            expectedEventType = null,
            batchListener = true
        )
    }

    @Bean(ConsumerGroups.CAPTURE_COMMAND_EXECUTOR + "-factory")
    fun captureCommandsFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory")
        customFactory: DefaultKafkaConsumerFactory<String, EventEnvelope<*>>,
        errorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<Event>> {
        return createFactory(
            clientId = "capture-command-executor",
            concurrency = captureCommandsConcurrency,
            interceptor = interceptor,
            consumerFactory = customFactory,
            errorHandler = errorHandler,
            expectedEventType = null
        )
    }

    @Bean(ConsumerGroups.CAPTURE_SUBMITTED_CONSUMER + "-factory")
    fun captureSubmittedAcksFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory")
        customFactory: DefaultKafkaConsumerFactory<String, EventEnvelope<*>>,
        errorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<Event>> {
        return createFactory(
            clientId = "capture-psp-performed-consumer",
            concurrency = captureSubmittedConcurrency,
            interceptor = interceptor,
            consumerFactory = customFactory,
            errorHandler = errorHandler,
            expectedEventType = null
        )
    }

    @Bean(ConsumerGroups.TRANSACTION_CONSUMER + "-factory")
    fun transactionConsumerFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory")
        customFactory: DefaultKafkaConsumerFactory<String, EventEnvelope<*>>,
        errorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<Event>> {
        return createFactory(
            clientId = "transaction-consumer",
            concurrency = 1,
            interceptor = interceptor,
            consumerFactory = customFactory,
            errorHandler = errorHandler,
            expectedEventType = null
        )
    }

    @Bean(ConsumerGroups.ACCOUNT_CREATION_COMMAND_EXECUTOR + "-factory")
    fun accountCreationFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory")
        customFactory: DefaultKafkaConsumerFactory<String, EventEnvelope<*>>,
        errorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<Event>> {
        return createFactory(
            clientId = "account-creation-command-executor",
            concurrency = 1,
            interceptor = interceptor,
            consumerFactory = customFactory,
            errorHandler = errorHandler,
            expectedEventType = null
        )
    }

    @Bean
    fun mdcRecordInterceptor(): RecordInterceptor<String, EventEnvelope<*>> = HeaderMdcInterceptor()

    @Bean
    fun messageHandlerMethodFactory(): DefaultMessageHandlerMethodFactory = DefaultMessageHandlerMethodFactory()

    private fun eventTypeFilter(expected: String): RecordFilterStrategy<String, EventEnvelope<*>> =
        RecordFilterStrategy { rec ->
            val serdeFailed = rec.headers().lastHeader(HDR_VALUE_BYTES) != null
            if (serdeFailed) {
                // let DefaultErrorHandler DLQ+commit it
                false
            } else {
                // only drop genuine wrong-type events
                rec.value()?.eventType != expected
            }
        }
}

class HeaderMdcInterceptor : RecordInterceptor<String, EventEnvelope<*>> {

    private val prevCtx = ThreadLocal<Map<String, String>?>()

    private fun putFrom(record: ConsumerRecord<String, EventEnvelope<*>>) {
        fun h(k: String) = record.headers().lastHeader(k)?.value()?.let { String(it) }
        val env = record.value()

        MDC.put(GenericLogFields.EVENT_ID, h("eventId") ?: env?.eventId?.toString())
        MDC.put(GenericLogFields.PARENT_EVENT_ID, h("parentEventId") ?: env?.parentEventId?.toString())
        MDC.put(GenericLogFields.AGGREGATE_ID, env?.aggregateId ?: record.key())
        MDC.put(GenericLogFields.EVENT_TYPE, h("eventType") ?: env?.eventType)
    }

    override fun intercept(
        record: ConsumerRecord<String, EventEnvelope<*>>,
        consumer: Consumer<String, EventEnvelope<*>>
    ): ConsumerRecord<String, EventEnvelope<*>>? {
        prevCtx.set(MDC.getCopyOfContextMap())
        putFrom(record)
        return record // return null to skip; we’re not skipping here
    }

    override fun afterRecord(
        record: ConsumerRecord<String, EventEnvelope<*>>,
        consumer: Consumer<String, EventEnvelope<*>>
    ) {
        val prev = prevCtx.get()
        if (prev != null) MDC.setContextMap(prev) else MDC.clear()
        prevCtx.remove()
    }
}
