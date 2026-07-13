Two Different Approaches to OpenTelemetry in Spring Boot
You're seeing two different approaches being mixed up in examples online. Here's the distinction:
    WITHOUT  AGENT  2 DIFFERENT APPROACG
Approach 1: OpenTelemetry Spring Boot Starter (recommended)
<!-- BOM for version management -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-instrumentation-bom</artifactId>
    <version>2.29.0</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>

<!-- The starter — this is all you need -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
</dependency>
This is the native OpenTelemetry starter — it handles everything automatically (spans, metrics, logs, exporters) via Spring autoconfiguration. [Getting started]

Approach 2: Micrometer + OTel bridge (Spring Boot's built-in tracing)
# 🔭 OpenTelemetry Integration Analysis
## For the Event-Driven Payment Platform (Spring Boot + Kotlin + Kafka + AKS)

> **Context:** This analysis is written for the Merchant-of-Record payment platform described in `my-custom-architecture.md`.
> Your stack: Spring Boot, Kotlin, Kafka, PostgreSQL (MyBatis), AKS (Azure), Orbstack local, custom Outbox pattern.
> Your goal: **See spans in Jaeger as fast as possible — zero confusion, zero guesswork.**

---

## 🗺️ Phase 1: High-Level Mapping — What Is This Repo?

### What it is
The [`opentelemetry-java-instrumentation`](https://github.com/open-telemetry/opentelemetry-java-instrumentation) repo is **the official OpenTelemetry Java Agent project**. It ships as a single fat JAR (`opentelemetry-javaagent.jar`) that you attach to any JVM application via `-javaagent`. It:

- **Dynamically injects bytecode** into your running classes at load time (no source code changes)
- Provides **auto-instrumentation** for 200+ libraries including Spring Boot, Kafka, PostgreSQL (JDBC), HikariCP
- Also ships **standalone library instrumentations** (no agent needed) for cases where you want fine-grained control

### What it is NOT
It is **not** your own observability platform. It is the *instrumentation layer* — it generates the raw telemetry data (spans, metrics, logs). You still need a *collector* and a *backend* (Jaeger) to visualize that data.

### The Three Entry Points That Matter For You

| Entry Point | Purpose | Your Use Case |
|---|---|---|
| `opentelemetry-javaagent.jar` | Zero-code-change auto-instrumentation via JVM agent | **Fastest path** — attach to all your services |
| `instrumentation/spring/starters/spring-boot-starter` | Spring Boot Starter for manual/mixed instrumentation | If you want finer control without the agent |
| `instrumentation-annotations` (`@WithSpan`) | Add custom business spans with a single annotation | For your `OutboxRelayJob`, consumer logic, etc. |

---

## 🔬 Phase 2: Instrumentation Analysis — Spring & Kafka Directories

### What Are the `instrumentation/spring/` Folders?

The `instrumentation/spring/` directory contains **21 sub-modules**, each one being the OTel instrumentation for a specific Spring library.

```
instrumentation/spring/
├── spring-batch-3.0               ← @Scheduled batch jobs
├── spring-boot-autoconfigure      ← Auto-wires WebMVC/WebFlux/Kafka interceptors
├── spring-boot-resources          ← Resource detector (sets service.name etc.)
├── spring-cloud-aws-3.0           ← Azure cousin — cloud resources
├── spring-cloud-gateway           ← API Gateway tracing
├── spring-core-2.0                ← Context propagation in Spring core
├── spring-data                    ← Repository layer (JPA/MyBatis adjacent)
├── spring-integration-4.1         ← Spring Integration channels
├── spring-jms                     ← JMS messaging
├── spring-kafka-2.7               ⭐ Your @KafkaListener consumers
├── spring-scheduling-3.1          ⭐ Your OutboxRelayJob (@Scheduled)
├── spring-webmvc-3.1..6.0         ⭐ Your REST controllers
├── spring-webflux                 ← Reactive (not your stack)
├── starters/spring-boot-starter   ← The Spring Boot starter that bundles it all
```

### Each Module Has Three Layers

Every Spring instrumentation folder follows this structure:
```
spring-kafka-2.7/
├── javaagent/    ← Bytecode injection (agent mode, zero code changes)
├── library/      ← Manual Java/Kotlin code you wire yourself
├── testing/      ← Abstract tests shared between both modes
└── metadata.yaml ← Declarative spec: what it instruments, config knobs
```

**The `metadata.yaml` is the contract.** It declares:
- What semantic conventions the instrumentation follows (e.g., `MESSAGING_SPANS`)
- What config knobs exist and their defaults
- Whether it is enabled by default or not

### Spring Kafka Instrumentation — Deep Dive (spring-kafka-2.7)

From [metadata.yaml](file:///Users/dogancaglar/IdeaProjects/opentelemetry-java-instrumentation/instrumentation/spring/spring-kafka-2.7/metadata.yaml):

```yaml
display_name: Spring Kafka
description: This instrumentation enables consumer messaging spans for Spring Kafka listeners.
semantic_conventions:
  - MESSAGING_SPANS
configurations:
  - name: otel.instrumentation.messaging.experimental.receive-telemetry.enabled
    default: false     # ← by default, consumer starts a NEW child span under producer span
  - name: otel.instrumentation.kafka.experimental-span-attributes
    default: false     # ← captures kafka.record.queue_time_ms & bootstrap.servers
```

**What this gives you automatically:**
- Every `@KafkaListener` method gets a **CONSUMER span** automatically
- **W3C TraceContext** headers are read from Kafka message headers
- The consumer span is linked to the **producer span** from `OutboxRelayJob`
- This means: you get an **end-to-end trace** from `POST /payments` → DB → OutboxRelay → Kafka → CaptureCommandExecutor → CaptureSubmitted → ...

### Spring Boot Autoconfigure Module

From [metadata.yaml](file:///Users/dogancaglar/IdeaProjects/opentelemetry-java-instrumentation/instrumentation/spring/spring-boot-autoconfigure/metadata.yaml):

```yaml
description: Auto-configures OpenTelemetry instrumentation for spring-web, spring-webmvc,
  and spring-webflux. It does not produce telemetry on its own.
configurations:
  - name: otel.instrumentation.kafka.autoconfigure-interceptor
    default: true    # ← AUTO-WIRES tracing interceptors on ConcurrentKafkaListenerContainerFactory
```

> **Critical insight:** When using the Java agent, this autoconfigure module automatically registers OTel interceptors on your `ConcurrentKafkaListenerContainerFactory` beans **without any code change**. Your `@KafkaListener` consumers get traced automatically.

### How Are Instrumentations "Injected"?

There are **two injection mechanisms** and they are not mutually exclusive:

```
┌─────────────────────────────────────────────────────────────────┐
│ Mode 1: Java Agent (javaagent/ folder)                          │
│                                                                 │
│ -javaagent:opentelemetry-javaagent.jar                         │
│          ↓                                                      │
│ ByteBuddy intercepts class loading                             │
│          ↓                                                      │
│ Injects advice bytecode BEFORE/AFTER methods                   │
│          ↓                                                      │
│ Spans created without touching your source code                │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ Mode 2: Library Instrumentation (library/ folder)               │
│                                                                 │
│ Add Maven/Gradle dependency                                     │
│          ↓                                                      │
│ Manually wire: KafkaTelemetry.wrap(consumer)                   │
│              : OpenTelemetryHandlerMappingFilter (WebMVC)       │
│          ↓                                                      │
│ You control exactly what gets traced                           │
└─────────────────────────────────────────────────────────────────┘
```

**For your use case: Java Agent is the right first step** — zero code changes, immediate visibility.

### Does This Carry Business Context or Infrastructure Context?

**Out of the box: infrastructure-level only.** The agent automatically creates:
- HTTP server spans with `http.method`, `http.route`, `http.status_code`
- Kafka consumer/producer spans with `messaging.destination`, `messaging.kafka.consumer.group`
- JDBC spans with `db.statement`, `db.operation`
- JVM metrics (heap, GC, threads)

**To carry business context** (e.g., `payment.intent.id`, `seller.id`, `capture.amount`), you need to add attributes manually using the `@WithSpan` annotation or the OTel API. This is **Phase 2 of your journey**, not Phase 1.

---

## ⚙️ Phase 3: Configuration & Integration

### The Declarative Config Bridge

The [declarative-config-bridge](file:///Users/dogancaglar/IdeaProjects/opentelemetry-java-instrumentation/declarative-config-bridge/README.md) module is an **internal abstraction layer** that lets instrumentation authors read config from either:
- Environment variables / system properties (`otel.inferred.spans.backup.diagnostic.files=true`)
- OR a YAML declarative config file (newer format)

**This is NOT something you need to use directly.** It is an internal bridge. Your configuration stays in system properties or env vars.

### Supported Libraries — Alignment With Your Stack

| Your Component | OTel Library | Auto-instrumented? | Notes |
|---|---|---|---|
| Spring Boot (payment-service, payment-consumers, etc.) | spring-boot-autoconfigure | ✅ Yes (agent) | Wires all Spring instrumentation |
| Spring WebMVC controllers (`POST /api/v1/payments`) | spring-webmvc-3.1+ | ✅ Yes | HTTP server spans + route |
| `@KafkaListener` consumers (CaptureCommandExecutor, PspResultConsumer, etc.) | spring-kafka-2.7 | ✅ Yes | Consumer messaging spans |
| Apache Kafka (raw producer from OutboxRelayJob) | kafka-clients-0.11+ | ✅ Yes (agent) | Producer spans + context propagation in headers |
| PostgreSQL (JDBC via MyBatis) | jdbc / hikaricp-3.0 | ✅ Yes | DB client spans + pool metrics |
| HikariCP connection pool | hikaricp-3.0 | ✅ Yes | DB pool metrics |
| Spring Scheduling (`@Scheduled OutboxRelayJob`) | spring-scheduling-3.1 | ✅ Yes | Scheduled task spans |
| Spring Security (Keycloak JWT) | spring-security-config-6.0 | ✅ Yes | Security context propagation |
| Azure (AKS resources) | azure-resources (contrib) | ✅ Yes | Included in spring-boot-starter |
| Logback (your logging) | logback 1.0+ | ✅ Yes | MDC auto-injection of trace_id/span_id |

> **Your stack is almost 100% covered out of the box.** There are NO significant gaps.

### What IS a Gap (Requires Custom Instrumentation)

| Gap | What to do |
|---|---|
| **Business metrics** (payment amounts, capture success rate, ledger balance counts) | Use OTel `Meter` API directly in your use cases |
| **Business spans** (e.g., "OutboxEvent dispatched", "CaptureExecutor retry attempt") | Use `@WithSpan` on your use case methods |
| **W3C TraceContext in Kafka headers** for your custom `EventEnvelope` | Agent handles this automatically for kafka-clients. Your `JacksonSerializationAdapter` adding `traceId` in the payload is *redundant with OTel* but harmless |
| **Outbox polling job detailed spans** | `@WithSpan` on `OutboxRelayJob.poll()` |

---

## 🏛️ Phase 4: Architectural Synthesis

### What You Actually Have vs. What You Want

```
YOU HAVE (your custom system):
  payment-service → edge DB → OutboxEvent.traceId (manual, bespoke)
  OutboxRelayJob reads traceId from DB → puts it in Kafka header (manual)
  PspResultConsumer reads traceId from Kafka header (manual)
  → This works, but is non-standard and creates coupling

WHAT OTel GIVES YOU (standard):
  payment-service → HTTP span created by agent
  → JDBC span for DB write
  → Kafka PRODUCER span (W3C header: traceparent)
  → Kafka CONSUMER span (reads traceparent header, continues same trace)
  → JDBC span for consumer DB write
  → All linked automatically in Jaeger as ONE trace
```

### The Fastest Path to See Spans in Jaeger

**Step 1: Run Jaeger + OTel Collector in-memory (Orbstack local)**

```yaml
# docker-compose.otel.yml
services:
  jaeger:
    image: jaegertracing/all-in-one:latest
    ports:
      - "16686:16686"   # Jaeger UI
      - "4317:4317"     # OTLP gRPC receiver (OTel Collector will forward here)
    environment:
      - COLLECTOR_OTLP_ENABLED=true

  # No separate collector needed for local dev!
  # Jaeger all-in-one accepts OTLP directly on port 4317/4318
```

> **No separate collector for local dev.** Jaeger `all-in-one` accepts OTLP natively. You get in-memory storage by default. Start simple.

**Step 2: Download the Java Agent JAR**

```bash
curl -L https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
  -o opentelemetry-javaagent.jar
```

**Step 3: Add JVM flags to each Spring Boot service**

In your Orbstack `docker-compose.yml` or `application-local.yml` JVM args:

```bash
java \
  -javaagent:/path/to/opentelemetry-javaagent.jar \
  -Dotel.service.name=payment-service \
  -Dotel.exporter.otlp.endpoint=http://localhost:4317 \
  -Dotel.exporter.otlp.protocol=grpc \
  -Dotel.traces.exporter=otlp \
  -Dotel.metrics.exporter=otlp \
  -Dotel.logs.exporter=otlp \
  -jar payment-service.jar
```

For each service, set a unique `otel.service.name`:
- `payment-service`
- `payment-consumers`
- `payment-central-relay`
- `payment-edge-workers`

**Step 4: Add trace_id to your logs (zero code change)**

In your `application.yml` for all services:

```properties
# application-local.properties
logging.pattern.level=trace_id=%mdc{trace_id} span_id=%mdc{span_id} %5p
```

This is the [Logger MDC auto-instrumentation](file:///Users/dogancaglar/IdeaProjects/opentelemetry-java-instrumentation/docs/logger-mdc-instrumentation.md) — **fully automatic**, no code change needed. The agent injects `trace_id` and `span_id` into your Logback MDC.

**Step 5: Open Jaeger UI at `http://localhost:16686`**

Search for service `payment-service` → you will see:
- Every `POST /api/v1/payments` HTTP request as a root span
- Child spans: JDBC selects, JDBC inserts (PaymentIntent, OutboxEvent)
- Kafka PRODUCE span (OutboxRelayJob → topic `gateway.capture.commands`)
- Linked to: Kafka CONSUME span in `payment-consumers` service
- Child spans of consumer: JDBC writes, nested Kafka produces

**This gives you the complete end-to-end trace across all your services without a single line of code change.**

---

## 📋 What Happens At Each Step of Your Payment Flow

| Your Flow Step | OTel Auto-Span | Span Kind | Attributes |
|---|---|---|---|
| `POST /api/v1/payments` received | ✅ HTTP Server Span | SERVER | `http.method=POST`, `http.route=/api/v1/payments` |
| `IdempotencyCheck` DB query | ✅ JDBC Span | CLIENT | `db.statement=SELECT...`, `db.system=postgresql` |
| `PaymentIntent` insert + OutboxEvent insert (transactional) | ✅ JDBC Span | CLIENT | `db.operation=INSERT` |
| `LocalOutboxForwarderJob` polls edge DB | ✅ Scheduling Span | INTERNAL | `code.namespace=LocalOutboxForwarderJob` |
| OutboxRelayJob publishes to Kafka | ✅ Kafka Producer Span | PRODUCER | `messaging.destination=gateway.capture.commands` |
| CaptureCommandExecutor `@KafkaListener` | ✅ Kafka Consumer Span | CONSUMER | `messaging.kafka.consumer.group=...` |
| PSP HTTP call (external Stripe/PSP) | ✅ HTTP Client Span | CLIENT | `http.url=https://api.stripe.com/...` |
| PspResultConsumer writes to central DB | ✅ JDBC Span | CLIENT | `db.statement=INSERT INTO outbox_event` |
| JournalEntry INSERT (ledger bookkeeping) | ✅ JDBC Span | CLIENT | `db.operation=INSERT`, `db.table=journal_entries` |

---

## 🎯 Your Confusion Resolved: Auto vs. Manual

```
                        ┌─────────────────────────────────┐
                        │       YOUR QUESTION              │
                        │  "Auto span vs manual — what     │
                        │   do I need to do first?"        │
                        └─────────────────────────────────┘
                                        │
              ┌─────────────────────────┴─────────────────────────┐
              │                                                    │
    ┌─────────▼──────────┐                             ┌──────────▼────────┐
    │   AUTO (Phase 1)   │                             │ MANUAL (Phase 2)  │
    │ -javaagent flag    │                             │ @WithSpan          │
    │ NO code changes    │                             │ OTel API           │
    │                    │                             │ Custom attributes  │
    │ You get:           │                             │                    │
    │ • HTTP spans       │                             │ You get:           │
    │ • Kafka spans      │                             │ • payment.id attr  │
    │ • DB spans         │                             │ • capture.amount   │
    │ • JVM metrics      │                             │ • seller.id        │
    │ • Log trace_id     │                             │ • business events  │
    │ • End-to-end trace │                             │                    │
    └────────────────────┘                             └───────────────────┘
              │                                                    │
    START HERE! Works in                          DO AFTER you see
    Orbstack in 30 minutes                        traces working
```

---

## 🚀 Recommended Rollout Phases

### Phase 1 (Now — Orbstack): Zero-Code Auto-Instrumentation
1. Start Jaeger all-in-one: `docker run -p 16686:16686 -p 4317:4317 jaegertracing/all-in-one`
2. Download `opentelemetry-javaagent.jar`
3. Add `-javaagent` flag to each service's JVM startup
4. Set `otel.service.name` per service
5. Set `logging.pattern.level` in logback for trace IDs in logs
6. Open Jaeger → see your first traces

### Phase 2 (AKS Prep): Add OTel Collector
```
Your Services → (OTLP) → OTel Collector → Jaeger
                                        → Prometheus
                                        → Azure Monitor (optional)
```

The OTel Collector acts as a buffer/router. For AKS, deploy it as a DaemonSet or sidecar. Uses in-memory queuing before export — exactly what you asked for.

### Phase 3 (Business Context): Custom Spans & Attributes
Add `@WithSpan` to your key use cases:
```kotlin
// payment-application
@WithSpan("process-psp-result")
fun processPspResult(event: EventEnvelope<PaymentAuthorized>) {
    Span.current().setAttribute("payment.intent.id", event.payload.paymentIntentId)
    Span.current().setAttribute("payment.authorized.amount", event.payload.amount.toString())
    // your existing logic
}
```

### Phase 4 (Production): Azure Monitor Integration
The Spring Boot Starter already includes `opentelemetry-azure-resources` (see [build.gradle.kts](file:///Users/dogancaglar/IdeaProjects/opentelemetry-java-instrumentation/instrumentation/spring/starters/spring-boot-starter/build.gradle.kts)) which automatically detects AKS pod identity, node name, and namespace as resource attributes.

---

## ⚠️ One Important Note About Your Current `traceId` in EventEnvelope

Your `JacksonSerializationAdapter` currently embeds a `traceId` field inside the Kafka message JSON payload (`EventEnvelope<T>`). When you add the OTel agent:

- The agent will **also** inject W3C `traceparent` headers into the Kafka message headers (separate from the JSON payload)
- The agent reads those headers on the consumer side to continue the trace
- **Your existing `traceId` in the payload is not redundant for your own business logic** (it is there for auditing/replaying), but OTel will use its own W3C headers for span correlation
- There is **no conflict** — they are orthogonal mechanisms

> [!TIP]
> Once OTel is running, you can remove the manual `traceId` propagation from your Kafka flow if you want to simplify, because OTel handles it properly. But there's no rush — they coexist safely.

---

## Summary

| Question | Answer |
|---|---|
| Is this repo core instrumentation or extensions? | **Core** — this IS the official OTel Java agent |
| Are Spring components custom filters or standard? | **Standard agent bytecode injection** via ByteBuddy, not Spring filters |
| How injected? | **JVM agent** (`-javaagent`) for zero-code, OR **Spring Boot Starter** for library mode |
| Infrastructure or business context? | **Infrastructure** OOTB, **business** via `@WithSpan` + OTel API |
| Does it align with Spring/Kafka/Azure? | **Yes, 100%** — Spring WebMVC, Spring Kafka, Kafka clients, JDBC, HikariCP, Azure resources all covered |
| Fastest path to see spans? | **-javaagent + Jaeger all-in-one** = working traces in under 1 hour |
| What about MDC/logs? | **Auto-injected** — just set `logging.pattern.level` pattern in Spring Boot |
| Collector needed? | **No** for local (Jaeger OTLP-native). **Yes** for AKS prod routing |
