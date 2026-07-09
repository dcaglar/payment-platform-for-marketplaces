# Payment Service (Edge Web API)

This service handles the synchronous HTTP requests, writes to its local edge database, and returns the response to the shopper. It does not publish to Kafka or talk to the central database directly.

```mermaid
flowchart TD
    %% C4 Styling
    classDef person fill:#08427b,color:#fff,stroke:#052e56,stroke-width:2px;
    classDef external fill:#999999,color:#fff,stroke:#666666,stroke-width:2px;
    classDef container fill:#438dd5,color:#fff,stroke:#2e6295,stroke-width:2px;
    classDef db fill:#438dd5,color:#fff,stroke:#2e6295,stroke-width:2px;
    classDef boundary fill:none,stroke:#666,stroke-width:2px,stroke-dasharray: 5 5;

    client["Person: Shopper<br/>[Client / Merchant App]"]:::person
    psp["System: External PSP<br/>[Stripe]"]:::external
    
    subgraph pod_api ["System Boundary: Payment Service & DB Pod"]
        api["Container: Payment Service<br/>[Kotlin, Spring Boot]<br/>Handles sync HTTP requests"]:::container
        edge_db[("Container: Edge DB 0<br/>[PostgreSQL]<br/>Local Edge Outbox & Idempotency")]:::db
    end

    client -- "1. POST /payments" --> api
    api -- "2. Check Idempotency" --> edge_db
    api -- "3. Sync Auth Request" --> psp
    psp -- "4. Auth Response" --> api
    api -- "5. Insert PaymentIntent & OutboxEvent" --> edge_db
    api -- "6. 201 Created Response" --> client
```
