## Discovery Protocol (READ BEFORE EXPLORING)
1. **Docs-first, always.** Before reading code or spawning ANY agent, read                                                                                                                                                  
   `docs/architecture/architecture.md` and `infra/scripts/deploy-all-local.sh`                                                                                                                                              
   (plus the scripts it calls). They are the source of truth for topology, the                                                                                                                                              
   Kafka event/topic catalog, DB URLs/creds, profiles, and the ledger flow.
2. **Agent budget.** Prefer direct Read/Grep. Do NOT spawn Explore/general-purpose                                                                                                                                          
   agents for anything findable in the docs or a targeted grep. Max ONE research                                                                                                                                            
   agent without asking me first; never run overlapping agents; never let one re-run.
3. **Scope before building.** For any new module / multi-file change, ask the 1–2                                                                                                                                           
   pivotal scoping questions (e.g. build vs. reuse existing images; where config lives)                                                                                                                                     
   BEFORE writing files.
4. **Verify docs vs code only where they plausibly diverge.** Known drifts to check:                                                                                                                                        
   real capture topic is `gateway.capture.requested` (doc says `.commands`);                                                                                                                                                
   eventType is `settlement_received` (doc says `settlement_received_by_psp`);                                                                                                                                              
   `MARKETPLACE-5` is a hardcoded PSP-sim target (code-only, not in docs).

# Repository Context & AI Assistant Guidelines

## 1. System Overview
This project is an Event-Driven Payment Platform for a Merchant-of-Record (MoR) environment, handling the full payment lifecycle across multi-seller checkout scenarios[cite: 2]. 
*   **Tech Stack:** Kotlin, Spring Boot, Maven, PostgreSQL, Kafka, Redis, and OpenTelemetry
*   **Core Patterns:** Domain-Driven Design (DDD), Hexagonal Architecture (Ports & Adapters), Double-Entry Ledger Accounting, and a Two-Stage Transactional Outbox[cite: 2].
*   **Topology:** The system is strictly divided into Edge Cells (handling synchronous API acceptance and local outboxing) and a Central Cluster (handling asynchronous ledger reconciliation and heavy PSP processing)[cite: 2].
*   **Environment:** You can follow [deploy-all-local.sh](/Users/dogancaglar/IdeaProjects/payment-platform-for-marketplaces/infra/scripts/deploy-all-local.sh) to understand how we use env name and spring profile names during the build.
## 📚 Key Documentation Files
- **[`Architecture Details`](./docs/architecture/architecture.md)** — Complete system architecture, entity models, flow diagrams, and design patterns,perfect for tracing 
- **[`How To Start`](./docs/how-to-start.md)** — Step-by-step setup guide to deploy infrastructure on orbstack local, its good to understand the infrastructure running platform.
- **[`Azure infrastructure details changelog`](./docs/architecture/adr-001-azure-infrastructure.md)** — Azure Infrastructure Details Changelog




## 2. Immutable Architectural Constraints (The Golden Rules)
When generating code, analyzing bugs, or suggesting changes, you **must strictly adhere** to these constraints:

*   **The Separation of Powers:** 
    *   `OutboxRelayJob` is the *only* component allowed to publish to Kafka[cite: 3]. It routes messages but never updates operational database state[cite: 3].
    *   Consumers (e.g., `PspResultConsumer`, `CaptureCommandExecutor`) are the *only* components allowed to mutate the core ledger/database[cite: 3]. They must *never* publish directly to Kafka[cite: 3]. They communicate downstream by appending new events to the Outbox[cite: 3].
*   **No Kafka Transactions:** Do not use Kafka Exactly-Once Semantics (EOS) or Kafka transactions[cite: 3]. The system relies purely on the Two-Stage Outbox Pattern for durability[cite: 3].
*   **Stateless Network Workers:** Workers interacting with the outside world (like PSPs) must execute the network call and append the result to the Outbox[cite: 3]. They must not alter core Payment domains[cite: 3].
*   **Type Preservation:** Use concrete generic types for `EventEnvelope<T>`[cite: 2]. Do not perform JSON deserialization in the `payment-central-relay`; use `RawEventPublisher` to stream raw bytes to Kafka[cite: 2].
*   **Observability:** Exclusively use the OpenTelemetry Spring Boot Starter (Push model)[cite: 2]. Do not use Micrometer or Java Agents[cite: 2]. Manual OTel context propagation is required for background outbox polling workers[cite: 2].

---

## 3. Module Boundaries & Responsibilities

### Core Hexagon Layers
*   **`payment-domain`**: Pure Kotlin business rules. Contains `Payment`, `PaymentIntent`, `JournalEntry`, `Account`, and `OutboxEvent` models[cite: 2]. Must have zero Spring or MyBatis dependencies[cite: 2].
*   **`payment-application`**: Coordinates business flows. Contains Use Cases, DTOs, Domain Events, and outbound Port interfaces (`LocalOutboxWriterPort`, `CentralOutboxRelayPort`)[cite: 2]. Manages internal fund distribution logic[cite: 2].
*   **`payment-infrastructure`**: Shared technical adapters. Implements Snowflake ID generation, Jackson serialization, and Redis caching[cite: 2]. 
*   **`common-db` & `common-kafka`**: Shared templates, JSONb typehandlers, and `EventEnvelope` SerDe configurations[cite: 2].

### Deployable Edge Components (Node Affinity Co-location)
*   **`payment-service`**: The Edge REST API[cite: 2]. Deployed together with edge-db under payment-edge-cell  folowing cell architecture.Handles synchronous checkouts, Stripe integration(or locally simulation), and writes to `local-edge-db` via `LocalOutboxWriterPort`[cite: 2]. Routed via NGINX Lua Snowflake ID matching[cite: 2].
*   **`payment-edge-workers`**: A standalone Pod on the Edge Cell that acts as the Local Sidecar Forwarder[cite: 2]. Polls the local outbox and pushes to the Central DB outbox using `CentralOutboxEdgePort`[cite: 2].

### Deployable Central Components (Anti-Affinity Separation)
*   **`payment-central-relay`**: A non-blocking service hosting the global `OutboxRelayJob`[cite: 2]. Polls the Central DB outbox behind `T_Safe` and publishes pre-serialized raw bytes to Kafka[cite: 2]. 
*   **`payment-consumers`**: The asynchronous processor[cite: 2]. Hosts `@KafkaListener` components for capture execution, PSP result processing, and double-entry ledger bookkeeping[cite: 2]. 
*   **`central-db`**:  Central postgreqsql db [central-db](charts/central-db/Chart.yaml) using helm template managed by helm templates 

---

## 4. Testing Execution
*   **Unit Tests (`*Test.kt`)**: Run via `mvn test`. Uses mocks, no external dependencies[cite: 3]. 
*   **Integration Tests (`*IntegrationTest.kt`)**: Run via `mvn verify`. Uses TestContainers and requires the `@Tag("integration")` annotation[cite: 3].
