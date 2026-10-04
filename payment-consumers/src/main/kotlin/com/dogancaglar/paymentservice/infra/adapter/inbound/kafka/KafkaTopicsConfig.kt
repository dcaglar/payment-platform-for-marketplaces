package com.dogancaglar.paymentservice.infra.adapter.inbound.kafka

import com.dogancaglar.common.kafka.metadata.Topics
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaAdmin

/** Per-topic overrides; keys come from KafkaTopicSetProperties.specs */
data class TopicSpec(
    var partitions: Int = 1,
    var replicas: Short = 1,
    var createDlq: Boolean = true
)

@ConfigurationProperties("app.kafka")
data class

KafkaTopicSetProperties(
    /** key MUST exactly match the base topic names in Topics.ALL */
    var specs: Map<String, TopicSpec> = emptyMap()
)

@Configuration
@EnableConfigurationProperties(KafkaTopicSetProperties::class)
class TopicAdminConfig(
    private val cfg: KafkaTopicSetProperties
) {
    /** Spring Boot will auto-create KafkaAdmin if spring.kafka.bootstrap-servers is set */
    @Bean
    fun newTopics(): KafkaAdmin.NewTopics {
        val logger = org.slf4j.LoggerFactory.getLogger(TopicAdminConfig::class.java)
        logger.info(
            "🛠️ TopicAdminConfig: Preparing to auto-create Kafka topics based on Topics.ALL and app.kafka.specs..."
        )
        val topics = mutableListOf<NewTopic>()
        for (base in Topics.ALL) {
            val spec = cfg.specs[base] ?: TopicSpec()
            logger.info(
                "🛠️ TopicAdminConfig: Queuing topic creation for '{}' (partitions: {}, replicas: {})",
                base,
                spec.partitions,
                spec.replicas
            )
            topics += NewTopic(base, spec.partitions, spec.replicas)
            if (spec.createDlq) {
                logger.info("🛠️ TopicAdminConfig: Queuing DLQ topic creation for '{}'", Topics.dlqOf(base))
                topics += NewTopic(Topics.dlqOf(base), spec.partitions, spec.replicas)
            }
        }
        logger.info("🛠️ TopicAdminConfig: Passing {} total topics to KafkaAdmin for creation.", topics.size)
        return KafkaAdmin.NewTopics(*topics.toTypedArray())
    }

    /** Explicit KafkaAdmin definition to guarantee topic creation even if spring.kafka.bootstrap-servers isn't set */
    @Bean
    fun kafkaAdmin(bootKafkaProps: org.springframework.boot.autoconfigure.kafka.KafkaProperties): KafkaAdmin {
        val logger = org.slf4j.LoggerFactory.getLogger(TopicAdminConfig::class.java)
        logger.info(
            "🛠️ TopicAdminConfig: Explicitly initializing KafkaAdmin bean to force auto-creation of topics on broker startup!"
        )
        val configs = java.util.HashMap<String, Any>()
        configs[org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG] = bootKafkaProps.bootstrapServers.joinToString(",")
        return KafkaAdmin(configs).apply {
            setAutoCreate(true)
            setFatalIfBrokerNotAvailable(false)
        }
    }
}
