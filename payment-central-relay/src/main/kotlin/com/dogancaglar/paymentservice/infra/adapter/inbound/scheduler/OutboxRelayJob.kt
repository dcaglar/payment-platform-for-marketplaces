package com.dogancaglar.paymentservice.infra.adapter.inbound.scheduler

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class OutboxRelayJob(
    private val dispatchWorker: CentralOutboxDispatchWorker
) {
    @Scheduled(
        initialDelayString = "\${outbox-relay.initial-delay:15000}",
        fixedDelayString = "\${outbox-relay.poll-interval:5000}"
    )
    fun poll() {
        dispatchWorker.centralOutboxRelayBatchWorker()
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 120_000)
    fun reclaimStuck() {
        dispatchWorker.reclaimStuck()
    }
}