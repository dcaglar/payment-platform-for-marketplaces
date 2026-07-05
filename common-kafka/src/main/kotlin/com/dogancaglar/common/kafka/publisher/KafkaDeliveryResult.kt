package com.dogancaglar.common.kafka.publisher

data class KafkaDeliveryResult(
    val topic: String,
    val partition: String,
    val offset: Long
)