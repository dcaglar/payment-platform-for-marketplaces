package com.dogancaglar.paymentservice.infra.adapter.inbound.shutdown

import com.dogancaglar.paymentservice.ports.outbound.LocalOutboxWriterPort
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * EdgeApiGracefulShutdownHook
 * 
 * Ensures that the payment-service API pod does not shut down (and take its local database with it)
 * until the remote payment-edge-worker pod has successfully drained all pending outbox events.
 */
@Component
class EdgeApiGracefulShutdownHook(
    private val localOutboxWriterPort: LocalOutboxWriterPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PreDestroy
    fun onShutdown() {
        logger.info("Step 1: API Pod Graceful shutdown initiated. Checking for pending local outbox events...")

        var warningLogged = false
        var emptyCycles = 0

        // We block termination as long as there are NEW or PROCESSING events in the local outbox.
        // We require 3 empty cycles to be absolutely sure the worker has finished sweeping.
        while (emptyCycles < 3) {
            val hasPending = try {
                localOutboxWriterPort.hasPendingEvents()
            } catch (t: Throwable) {
                logger.error("Failed to query outbox status during shutdown. Proceeding with termination to avoid permanent hang.", t)
                break
            }

            if (hasPending) {
                if (!warningLogged) {
                    logger.warn("Step 2: Pending outbox events detected! Blocking API Pod termination until payment-edge-worker drains them.")
                    warningLogged = true
                }
                emptyCycles = 0
                Thread.sleep(1000)
            } else {
                emptyCycles++
                if (warningLogged) {
                    logger.info("   -> Empty cycle {}/3: No pending events found.", emptyCycles)
                }
                Thread.sleep(1000)
            }
        }

        logger.info("Step 3: Local outbox is completely drained. API Pod is now cleared to terminate.")
    }
}
