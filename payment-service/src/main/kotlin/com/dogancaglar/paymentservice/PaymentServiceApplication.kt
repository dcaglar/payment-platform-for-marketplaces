package com.dogancaglar.paymentservice

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableAsync
class PaymentServiceApplication

fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("JVMUncaughtExceptionHandler")
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        logger.error("Uncaught exception in thread ${t.name}", e)
    }
    runApplication<PaymentServiceApplication>(*args)
}
