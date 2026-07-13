# Payment Edge Workers (Local Sidecar Forwarder)

This background worker bridges the gap between the isolated edge cells and the central cluster. It strictly reads from its local edge outbox and pushes to the central outbox.

```mermaid
flowchart TD
    %% C4 Styling
    classDef container fill:#438dd5,color:#fff,stroke:#2e6295,stroke-width:2px;
    classDef db fill:#438dd5,color:#fff,stroke:#2e6295,stroke-width:2px;
    classDef boundary fill:none,stroke:#666,stroke-width:2px,stroke-dasharray: 5 5;

    subgraph pod_worker ["System Boundary: Payment Edge Worker Pod"]
        worker["Container: Payment Edge Workers<br/>[Kotlin, Spring Boot]<br/>LocalOutboxForwarderJob"]:::container
    end

    subgraph pod_api ["System Boundary: Payment Service & DB Pod"]
        edge_db[("Container: Edge DB 0<br/>[PostgreSQL]<br/>Local Outbox Table")]:::db
    end

    subgraph central_cluster ["System Boundary: Central Infrastructure"]
        central_db[("Container: Central DB<br/>[PostgreSQL]<br/>Global Outbox Table")]:::db
    end

    worker -- "1. Poll UNPROCESSED outbox events" --> edge_db
    worker -- "2. Insert events in batch" --> central_db
    worker -- "3. Mark events as DISPATCHED" --> edge_db
```
