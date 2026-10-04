package com.dogancaglar.paymentservice.infra.adapter.inbound

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

@Component
@Profile("liquibase-job")
class LiquibaseJobExiter(private val context: ApplicationContext) : CommandLineRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(vararg args: String?) {
        logger.info("=========================================================")
        logger.info("Liquibase migration completed successfully. Exiting JVM.")
        logger.info("=========================================================")

        val exitCode = SpringApplication.exit(context)
        exitProcess(exitCode)
    }
}
