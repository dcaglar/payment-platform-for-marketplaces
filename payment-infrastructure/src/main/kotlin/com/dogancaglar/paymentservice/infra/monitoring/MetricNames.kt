package com.dogancaglar.paymentservice.infra.monitoring

// MetricNames.kt
object MetricNames {
    // Outbox
    const val OUTBOX_DISPATCHED_TOTAL = "outbox_dispatched_total"
    const val OUTBOX_DISPATCH_FAILED_TOTAL = "outbox_dispatch_failed_total"
    const val OUTBOX_EVENT_BACKLOG = "outbox_event_backlog"
    const val OUTBOX_DISPATCHER_DURATION = "outbox_dispatcher_duration" // <- ✅ no “_seconds”
    /*
    const val OUTBOX_CLEANUP_DELETED_TOTAL = "outbox_cleanup_total_deleted"
    const val OUTBOX_CLEANUP_LAST_BATCH_DELETED_SIZE = "outbox_cleanup_last_batch_deleted_size"
    const val OUTBOX_CLEANUP_BATCH_NUMBER_PER_CLEANUP = "outbox_cleanup_batch_number_per_cleanup"
    const val OUTBOX_CLEANUP_DURATION_SECONDS = "outbox_cleanup_duration_seconds"
    const val OUTBOX_PUBLISH_FAILED_TOTAL = "outbox_publish_failed_total"
    const val OUTBOX_EVENT_BACKLOG = "outbox_event_backlog"
    const val OUTBOX_DISPATCH_DELAY_SECONDS = "outbox_dispatch_delay_seconds"
    const val OUTBOX_JOB_DURATION_SECONDS = "outbox_job_duration_seconds"
    const val OUTBOX_DB_WRITE_SECONDS = "outbox_db_write_seconds"

     */
}

// MetricTags.kt
object MetricTags {
    const val FLOW = "flow"
    const val EVENT_TYPE = "eventType"
    const val JOB_NAME = "job"
}

object MetricTagValues {
    object Jobs {
        const val OUTBOX_DISPATCHER = "outbox_dispatcher"
        const val RETRY_SCHEDULER = "retry_scheduler"
        // ... add as needed
    }

    object Flows {
        const val OUTBOX = "outbox"
        const val PAYMENT = "payment"

        // ... add as needed
    }

    object EventTypes {
        const val PAYMENT_ORDER_CREATED = "payment_order_capture_received"
        const val PAYMENT_ORDER_SUCCEEDED = "payment_order_succeeded"
        const val PAYMENT_ORDER_FAILED = "payment_order_failed"
        // ... add as needed
    }
    // Status, Topic, Group—fill as needed
}
