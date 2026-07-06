// KafkaTypedConsumerFactoryConfig.kt
package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka

import com.dogancaglar.common.time.Utc
import com.dogancaglar.common.event.Event
import com.dogancaglar.common.event.EventEnvelope
import com.dogancaglar.common.logging.GenericLogFields
import com.dogancaglar.common.kafka.serde.EventEnvelopeKafkaSerializer
import com.dogancaglar.common.kafka.metadata.Topics
import com.dogancaglar.common.kafka.metadata.CONSUMER_GROUPS
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.*
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.RecordInterceptor
import org.springframework.kafka.listener.adapter.RecordFilterStrategy
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory
import java.io.PrintWriter
import java.lang.ClassCastException
import java.lang.IllegalArgumentException
import java.lang.NullPointerException
import java.sql.SQLTransientException
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.ConsumerConfig.CLIENT_ID_CONFIG
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.context.annotation.Profile
import org.springframework.core.convert.ConversionException
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.NonTransientDataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.kafka.support.KafkaHeaders.GROUP_ID
import org.springframework.kafka.support.serializer.DeserializationException
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException
import java.io.StringWriter

@Configuration
class KafkaTypedConsumerFactoryConfig(
    private val bootKafkaProps: KafkaProperties,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.kafka.concurrency.journal-entries:3}") private val journalEntriesConcurrency: Int,
    @Value("\${app.kafka.concurrency.capture-commands:3}") private val captureCommandsConcurrency: Int,
    @Value("\${app.kafka.concurrency.capture-submitted:3}") private val captureSubmittedConcurrency: Int
) {
    companion object {
        private val logger = LoggerFactory.getLogger(KafkaTypedConsumerFactoryConfig::class.java)
        private const val HDR_VALUE_BYTES = "springDeserializerExceptionValue"
    }

    @Bean("custom-kafka-consumer-factory-for-micrometer")
    fun defaultKafkaConsumerFactory(): DefaultKafkaConsumerFactory<String, EventEnvelope<*>> {
        val configs = bootKafkaProps.buildConsumerProperties().toMutableMap()
        return DefaultKafkaConsumerFactory<String, EventEnvelope<*>>(configs).apply {
            addListener(MicrometerConsumerListener(meterRegistry))
        }
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
        factory.addListener(MicrometerProducerListener<String, ByteArray>(meterRegistry))
        return factory
    }

    @Bean("dlqKafkaTemplate")
    fun dlqKafkaTemplate(
        @Qualifier("dlqProducerFactory") pf: ProducerFactory<String, ByteArray>
    ) = KafkaTemplate(pf).apply {
        setObservationEnabled(true)
    }

    @Bean
    fun errorHandler(
        @Qualifier("dlqKafkaTemplate") dlqTemplate: KafkaTemplate<String, ByteArray>,
        kafkaExponentialBackOff: ExponentialBackOffWithMaxRetries
    ): DefaultErrorHandler {

        val recoverer = ConsumerRecordRecoverer { rec, ex ->
            val src = rec.topic()
            val target = if (src.endsWith(".DLQ")) src else Topics.dlqOf(src)
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
                add("x-error-message", ((ex?.message ?: "")
                    .take(8_000)).toByteArray()) // cap to avoid jumbo headers
                add("x-error-stacktrace", stackTraceString(ex, 16_000).toByteArray())
                add("x-recovered-at", Utc.nowInstant().toString().toByteArray())
                add("x-consumer-group", (rec.headers()
                    .lastHeader(GROUP_ID)?.let { String(it.value()) }
                    ?: "unknown").toByteArray())
            }

            val pr = ProducerRecord<String, ByteArray>(
                target,
                rec.partition(),
                null,
                key,
                valueBytes,
                headers
            )
            
            meterRegistry.counter(
                "kafka_consumer_error_total",
                "client_id", rec.headers().lastHeader(CLIENT_ID_CONFIG)?.value()?.let { String(it) } ?: "unknown",
                "topic", rec.topic()
            ).increment()

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
            )
        }
    }


    private fun stackTraceString(ex: Throwable?, max: Int): String =
        if (ex == null) "" else StringWriter().use { sw ->
            ex.printStackTrace(PrintWriter(sw))
            sw.toString().take(max)
        }

    @Bean
    fun kafkaExponentialBackOff(): ExponentialBackOffWithMaxRetries =
        ExponentialBackOffWithMaxRetries(5).apply {
            initialInterval = 2_000L
            multiplier = 2.0
            maxInterval = 30_000L
        }


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
            containerProperties.pollTimeout = 1000           // block up to 1s waiting for data
            containerProperties.isMicrometerEnabled = true
            containerProperties.isObservationEnabled = true
            containerProperties.idleBetweenPolls = 250 // nap 250ms after an empty poll
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








    @Bean(CONSUMER_GROUPS.PSP_RESULT_CONSUMER + "-factory")
    fun pspResultFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory-for-micrometer")
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

    @Bean(CONSUMER_GROUPS.WEBHOOK_CAPTURE_CONFIRMED_PROCESSOR + "-factory")
    fun marketPlaceSplitConsumerFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory-for-micrometer")
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

    @Profile("test", "local","azure")
    @Bean(CONSUMER_GROUPS.SETTLEMENT_RECORD_SIMULATOR + "-factory")
    fun settlementSimulatorFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory-for-micrometer")
        customFactory: DefaultKafkaConsumerFactory<String, EventEnvelope<*>>,
        errorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<Event>> {
        return createFactory(
            clientId = CONSUMER_GROUPS.SETTLEMENT_RECORD_SIMULATOR,
            concurrency = 1,
            interceptor = interceptor,
            consumerFactory = customFactory,
            errorHandler = errorHandler,
            expectedEventType = null
        )
    }



    @Bean(CONSUMER_GROUPS.ACCOUNT_BALANCE_CONSUMER + "-factory")
    fun journalEntriesRecordedFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory-for-micrometer")
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



    @Bean(CONSUMER_GROUPS.CAPTURE_COMMAND_EXECUTOR + "-factory")
    fun captureCommandsFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory-for-micrometer")
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

    @Bean(CONSUMER_GROUPS.CAPTURE_SUBMITTED_CONSUMER + "-factory")
    fun captureSubmittedAcksFactory(
        interceptor: RecordInterceptor<String, EventEnvelope<*>>,
        @Qualifier("custom-kafka-consumer-factory-for-micrometer")
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