# Payment Consumers (Asynchronous Workers)

This service contains the core asynchronous business logic, executing commands (like Captures) against the PSP and updating the global ledger state.

```mermaid
  %% C4 Styling
    classDef external fill:#999999,color:#fff,stroke:#666666,stroke-width:2px;
    classDef container fill:#438dd5,color:#fff,stroke:#2e6295,stroke-width:2px;
    classDef db fill:#438dd5,color:#fff,stroke:#2e6295,stroke-width:2px;
    classDef queue fill:#438dd5,color:#fff,stroke:#2e6295,stroke-width:2px;
    classDef boundary fill:none,stroke:#666,stroke-width:2px,stroke-dasharray: 5 5;

    psp["System: External PSP<br/>[Stripe]"]:::external

    subgraph central_cluster ["System Boundary: Central Infrastructure"]
        kafka["Container: Kafka Cluster<br/>[Kafka Topics]"]:::queue
        consumers["Container: Payment Consumers<br/>[Kotlin, Spring Boot]<br/>@KafkaListeners"]:::container
        central_db[("Container: Central DB<br/>[PostgreSQL]<br/>Ledger, Tx, Payment")]:::db
    end

    kafka -- "1. Consume messages" --> consumers
    consumers -- "2. Execute Async PSP Ops (e.g. Capture)" --> psp
    consumers -- "3. Append Ledger Entries / Txs / OutboxEvents" --> central_db
    central_db -- "4. DB Commit" --> consumers
    consumers -- "5. Commit Kafka Offset" --> kafka
```
