## Discovery Protocol (READ BEFORE EXPLORING)
1. **Grounding, by need — not a ritual.** Per-module rules **auto-load** from each module's `CLAUDE.md` (`<module>/CLAUDE.md`); that covers single-module work — go straight to source. For **cross-module flow** ("what happens end-to-end"), read the **executable spec** `e2e-tests/.../PaymentFlowE2EIntegrationTest.kt` (milestones M0–M15, verified — it can't drift like prose). Use `docs/architecture/architecture.md` only for **topology diagrams + design rationale**, and `infra/scripts/deploy-all-local.sh` for infra wiring — both **on-demand, NOT mandatory prereads**. State grounding truthfully: if you go straight to source, say so — don't claim a doc you didn't open.
2. **Agent budget.** Prefer direct Read/Grep. Do NOT spawn Explore/general-purpose
   agents for anything findable in the docs or a targeted grep. Max ONE research
   agent without asking me first; never run overlapping agents; never let one re-run.
3. **Scope before building.** For any new module / multi-file change, ask the 1–2
   pivotal scoping questions (e.g. build vs. reuse existing images; where config lives)
   BEFORE writing files.

# Repository Context & AI Assistant Guidelines

## 1. System Overview
This project is an Event-Driven Payment Platform for a Merchant-of-Record (MoR) environment, handling the full payment lifecycle across multi-seller checkout scenarios[cite: 2]. 
*   **Tech Stack:** Kotlin, Spring Boot, Maven, PostgreSQL, Kafka, Redis, and OpenTelemetry
*   **Core Patterns:** Domain-Driven Design (DDD), Hexagonal Architecture (Ports & Adapters), Double-Entry Ledger Accounting, and a Two-Stage Transactional Outbox[cite: 2].
*   **Topology:** The system is strictly divided into Edge Cells (handling synchronous API acceptance and local outboxing) and a Central Cluster (handling asynchronous ledger reconciliation and heavy PSP processing)[cite: 2].
*   **Environment/Infrasracture:** You can follow [how to start](/Users/dogancaglar/IdeaProjects/payment-platform-for-marketplaces/docs/how-to-start.md) to understand the infrasractuure the application jobs running on and deployments.
## 📚 Key Documentation Files
- **[`Architecture Details`](./docs/architecture/architecture.md)** — Complete system architecture, entity models, flow diagrams, and design patterns,perfect for tracing 
- **[`How To Start`](./docs/how-to-start.md)** — Step-by-step setup guide to deploy infrastructure on orbstack local, its good to understand the infrastructure running platform.
- **[`Azure infrastructure details changelog`](./docs/architecture/adr-001-azure-infrastructure.md)** — Azure Infrastructure Details Changelog
- **[`Code quality and security checks`](./docs/code-quality.md)** — detekt, gitleaks, Trivy: when each runs, its baseline, how to fix findings

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

## 3. Module Map (detail is in each module's own CLAUDE.md)
Each module directory has a `CLAUDE.md` that auto-loads when you work there — read those for per-module rules. The Golden Rules (§2) stay global.

Inner hexagon (libraries, not deployable):
*   **`payment-domain`** — pure Kotlin domain model (aggregates, VOs, double-entry); zero Spring/MyBatis.
*   **`payment-application`** — use cases + inbound/outbound ports; WHAT not HOW; framework-clean.
*   **`common`** — `EventEnvelope<T>` kernel, `PublicId` codec, `Utc`, event-metadata registry.
*   **`common-db`** — domain⇄row entity mappers + MyBatis type handlers.
*   **`common-kafka`** — `RawEventPublisher`, envelope SerDe, `Topics`/metadata catalogs.
*   **`payment-infrastructure`** — outbound adapters (Snowflake, Redis, Jackson, resilience, OTel metrics).

Deployable Spring Boot apps:
*   **`payment-service`** — Edge REST API + the composition root (wires the framework-clean use cases as beans).
*   **`payment-edge-workers`** — local→central outbox forwarder; own pod, pinned 1:1 to one edge-db.
*   **`payment-central-relay`** — the ONLY Kafka publisher; polls central outbox behind `T_Safe`, streams raw bytes.
*   **`payment-consumers`** — the ONLY ledger mutators; `@KafkaListener`s, dedup, double-entry, downstream via outbox.
*   **`central-db`** — central Postgres (Helm-managed).

---

## 4. Testing Execution
*   **Unit Tests (`*Test.kt`)**: Run via `mvn test`. Check how [ci.yml](.github/workflows/ci.yml) run unit test
*   **Integration Tests (`*IntegrationTest.kt`)**: Check how [ci.yml](.github/workflows/ci.yml) run unit test
*   **Regression Tests (`*E2EIntegrationTest.kt`)**:  Check how [ci.yml](.github/workflows/ci.yml) run unit test
---

## 5. Error Handling Standard
Catch an exception only where you can do something useful with it; everywhere else let it propagate. Exactly three places catch:
*   **Adapters (edge to the outside world: PSP, DB, HTTP):** translate foreign exceptions (Stripe, SQL/Spring, HTTP status, transport errors) into **our own** exception types. Classify by meaning — was it done? *yes / no-won't / no-but-might-next-time / don't-know / our-fault* — using exception type, HTTP status, transport error, and provider error code; **never message text**. Adapters never decide business state.
*   **Application services:** catch only where the error decides what happens to our data (intent status, idempotency key). Otherwise throw our own typed exceptions.
*   **Controller advice (`@RestControllerAdvice`):** the single place that maps our exception types to HTTP (status, stable body `code`, `Retry-After`) and logs **once**. It never references provider types.

Never:
*   catch-log-rethrow in every layer (log once, at the layer that handles it);
*   catch and swallow — including async callbacks (`whenComplete*`): failures must be logged, alerted, and recoverable;
*   wrap in a generic `RuntimeException` (it hides the type the advice needs);
*   use exceptions for normal outcomes (a decline is a result, not an error);
*   leave an entity in a waiting state that nothing will move (every pending state needs an owner: background call, client retry, provider callback, or status check).

A 4xx from a provider means **we** (the caller) were wrong — never pass it through to our client unchanged; translate by what our client should do next.

---

## 6. Code Quality & Security Gates
Detail in [`docs/code-quality.md`](./docs/code-quality.md). Three tools, each with a **baseline** of findings still to fix. A finding in the baseline is tolerated; **any new finding fails the build**. Baselines only shrink: never add a line to make a build pass, except a verified false positive in `.gitleaksignore` / `.trivyignore` (say what it is in a comment).

*   **detekt** (Kotlin rules + ktlint formatting via `detekt-formatting`, 1.23.8, no type resolution): runs in `mvn verify` / `install` of every module, **not** in `mvn test`. Config `config/detekt/detekt.yml`, baseline `<module>/detekt-baseline.xml`. Rules you will hit: `MaxLineLength` 120, `ImportOrdering` (`*,java.**,javax.**,kotlin.**,^`), `ArgumentListWrapping`, `Wrapping`. Fix only your changed files: `infra/scripts/detekt-changed.sh` (auto-fixes formatting), `--check` to list only. `InstanceOfCheckForException` is off on purpose: §5 requires the exhaustive `when (e)` pattern.
*   **gitleaks** (secrets): pre-commit hook `.githooks/pre-commit` on staged changes (enable once: `git config core.hooksPath .githooks`); full history in the security scan. Never print or write a real key/token anywhere, including docs and test fixtures.
*   **Trivy** (HIGH/CRITICAL fixable CVEs in dependencies; misconfigurations in Dockerfiles, Helm charts, Terraform): baseline `.trivyignore`, every line says what fixes it. Fix a dependency CVE by raising the Boot-managed version through Boot's own override property in the root `pom.xml` (`jackson-bom.version`, `tomcat.version`, ...), not by pinning the single artifact.
*   **Run both scanners:** `infra/scripts/security-scan.sh` (identical to the CI job "Security Scan"; Docker images, no install).

Before handing back a change: `mvn clean install -DskipITs` green (compile + unit tests + detekt). If a `pom.xml`, Dockerfile, chart or Terraform file changed, also `infra/scripts/security-scan.sh`. Integration, e2e and cluster checks only when asked.
