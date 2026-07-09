# Payment Central Relay (Central Outbox Publisher)

This service runs a highly available scheduled job that acts as the final bridge from the database infrastructure into the Kafka messaging cluster.

```mermaid
    %% C4 Styling
    classDef container fill:#438dd5,color:#fff,stroke:#2e6295,stroke-width:2px;
    classDef db fill:#438dd5,color:#fff,stroke:#2e6295,stroke-width:2px;
    classDef queue fill:#438dd5,color:#fff,stroke:#2e6295,stroke-width:2px;
    classDef boundary fill:none,stroke:#666,stroke-width:2px,stroke-dasharray: 5 5;

    subgraph central_cluster ["System Boundary: Central Infrastructure"]
        central_db[("Container: Central DB<br/>[PostgreSQL]<br/>Global Outbox Table")]:::db
        relay["Container: Payment Central Relay<br/>[Kotlin, Spring Boot]<br/>OutboxRelayJob"]:::container
        kafka["Container: Kafka Cluster<br/>[Topics: gateway.*, payment.*]"]:::queue
    end

    relay -- "1. Poll UNPROCESSED events (behind T_Safe)" --> central_db
    relay -- "2. Publish EventEnvelope" --> kafka
    kafka -- "3. Broker ACK (Success)" --> relay
    relay -- "4. Mark event DISPATCHED" --> central_db
```
