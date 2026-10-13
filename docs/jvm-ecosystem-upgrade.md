# JVM ecosystem upgrade: how we move Spring Boot and friends

Tracking: GitHub milestone [JVM ecosystem upgrade: Spring Boot 4.1 / Spring Framework 7](https://github.com/dcaglar/payment-platform-for-marketplaces/milestone/4),
issues #239, #240, #241. Step 1 was PR #238.

This document records the strategy, the concrete steps, and why the work is split
the way it is. It is written so the same approach can be reused for the next major
jump (Boot 5, Kotlin 3, whatever comes).

## Where we started and where we go

| Component | Before (main, 2026-10-10) | Step 1 (PR #238) | Step 2 (#239) | Target after step 4 |
|---|---|---|---|---|
| Spring Boot | 3.5.0 | 3.5.16 | 4.1.1 | 4.1.x |
| Spring Framework | 6.2.x | 6.2.x | 7.0.9 | 7.0.x |
| Kotlin | 2.1.0 | 2.2.21 | 2.3.21 | 2.4.21 (step 3) |
| Jackson | 2.19 | 2.19 | 2.21.5 via `spring-boot-jackson2` bridge | 3.1.5 (`tools.jackson`), no Jackson 2 on the classpath |
| Spring Kafka / kafka-clients | 3.3 / 3.9 | 3.3 / 3.9 | 4.1.1 / 4.2.1 | same |
| JUnit | 5 | 5 | 6.0.3 | same |
| mybatis-spring-boot-starter | 3.0.3 | 3.0.5 | 4.1.0 | same |
| Boot starters | Boot 3 all-in-one | same | `*-classic` bridges | one modular starter per technology |

## The rule behind the split: one kind of change per PR

A framework major brings four different kinds of change. They fail in different
ways and are debugged with different tools, so we never mix them in one PR:

1. **Version drift inside the current major.** Patch releases, deprecation
   warnings, security pins. Fails at compile time or in the scanner. Cheap.
2. **The major version jump.** New framework behaviour: moved packages,
   nullability annotations, renamed APIs, changed defaults. Fails at compile
   time first, then at startup, then in integration tests.
3. **Serialization format change.** Jackson 2 to 3. Compiles fine, starts fine,
   and corrupts data on the wire or in the outbox table if a default changed.
   Only a byte-for-byte comparison catches it.
4. **Dependency re-wiring.** Replacing all-in-one starters with per-technology
   ones. Compiles fine. A forgotten starter shows up as a missing bean at
   runtime or as a silently disabled feature.

When a PR contains only one kind, a red gate has one cause and the fix is
obvious. When it contains two, every failure needs a bisection first.

The second rule: **every step is green on the full gate set before the next
begins.** The gates are `mvn clean verify` (unit + integration), the e2e module,
`infra/scripts/security-scan.sh`, and for steps that change runtime behaviour a
deploy on the local cluster with one payment reaching SETTLED and traces in Tempo.

## The steps

### Step 1: last release of the current major, deprecations cleared (PR #238)

Boot 3.5.0 to 3.5.16, Kotlin 2.1 to 2.2.21, mybatis starter 3.0.5, dead
version properties removed, jsr305 dropped, every deprecation warning fixed
(Lettuce `eval`/`flushAll`, RestClient), security pins (lz4-java, PostgreSQL),
`.trivyignore` pruned from 56 to 17 lines.

Why first: the last 3.5.x carries deprecation warnings for exactly the APIs
Boot 4 removes. Fixing them while still on Boot 3 means the compiler, not the
new framework, tells us what to change, and the fix is testable in a known-good
environment. It also shrinks the step 2 diff to only what Boot 4 itself forces.

### Step 2: the major jump, with every bridge Boot ships (#239, this branch)

Parent 4.1.1, Kotlin 2.3.21, mybatis starter 4.1.0, springmockk 5.0.1.
Boot 4 provides four deprecated bridges that make it behave like Boot 3, and
we take all of them:

| Bridge | What it keeps working | Removed in |
|---|---|---|
| `spring-boot-starter-classic` | the Boot 3 all-in-one auto-configuration catalogue | step 4 |
| `spring-boot-starter-test-classic` | all test slices (`@WebMvcTest`, `@MybatisTest`, ...) from one jar | step 4 |
| `spring-boot-jackson2` + `spring.http.converters.preferred-json-mapper=jackson2` | Jackson 2 auto-config; API JSON written by our own `ObjectMapper` | step 3 |
| `spring-boot-properties-migrator` (runtime) | logs every renamed or removed `application.yml` key at startup | step 4 |

What still had to change in code: moved Boot packages
(`org.springframework.boot.<technology>.autoconfigure`), JSpecify nullability
(`queryForList` returns `List<T?>`, `Jwt.getClaimAsString` returns `String?`,
Spring Kafka factory getters nullable), the aop starter renamed to aspectj,
Spring Data Redis `Duration` overloads, JUnit 4 asserts gone from the test
classpath, one property rename the migrator found.

Why with bridges: the classpath stays identical to Boot 3, so the only variable
is the framework version. Everything that breaks is a Framework 7 behaviour
change and nothing else. The bridges are deprecated, not removed, so there is
no deadline pressure inside this step.

### Step 3: Jackson 3 (#240, 2026-10-13)

`com.fasterxml.jackson` to `tools.jackson` in 29 files (annotations keep their
package), the three hand-built mappers to `JsonMapper.builder()` with the Kotlin
module, `java.time` support is built in so `jackson-datatype-jsr310` is gone,
checked `JsonProcessingException` to unchecked `JacksonException` at four catch
sites (one of them hid behind `IOException` in the PSP HTTP adapter), the
`myObjectMapper` bean typed `JsonMapper` so Boot's own backs off and the HTTP
converters use it, `spring-boot-jackson2` and the `preferred-json-mapper` pin
removed. The last Jackson 2 holders went too: `logstash-logback-encoder`
7.4 to 9.0 (its first Jackson 3 release), and the OTel starter's unused runtime
extras excluded, the declarative YAML config module and the four cloud
resource detectors. No `com.fasterxml.jackson` parser code is left; only
`jackson-annotations` 2.x, which Jackson 3 keeps on purpose (same package, no
code). The five Jackson 2 CVE lines left `.trivyignore`.

Kotlin 2.3.21 to 2.4.21 in the same step. detekt 1.23.8 runs without type
resolution, so it only parses the sources and does not care which compiler
built the classes; the build, detekt and all suites passed unchanged.

Golden files: `EventWireCompatibilityTest` in common-kafka keeps one recorded
envelope per event type under `src/test/resources/wire/`, written by the
Jackson 2 code before the switch, and `CreatePaymentBodyHashTest` keeps the
idempotency hash of the sample create request. Both passed unchanged on Jackson 3.

Why its own step: JSON bytes are a contract in this system. Outbox payloads
travel edge to central to Kafka, and events already stored in the outbox
tables must still deserialize after the upgrade. Jackson 3 changed defaults
(date format, unknown properties, module discovery). The step therefore adds a
test that serializes every event type and compares with JSON captured from the
step 2 code. That test only makes sense when nothing else changed in between.

Why before step 4: the Jackson module each app needs depends on which Jackson
it uses. Choosing modular starters before Jackson 3 means choosing twice.

### Step 4: modular starters, delete every bridge (#241)

Per module, declare exactly the Boot 4 starters used: `spring-boot-starter-webmvc`,
`-kafka`, `-jdbc`, `-data-redis`, `-liquibase`, `-actuator`,
`-security-oauth2-resource-server`, `-aspectj`, and the matching `-test`
companions. Remove the classic starters and the migrator. Libraries
(`common`, `payment-application`) likely drop the Boot starter altogether.

Why last: this is the step where a mistake is silent. Nothing fails to compile
when payment-consumers loses its Liquibase auto-configuration. Doing it alone,
with everything else already stable, means a missing bean at startup or a red
e2e has exactly one explanation: a starter is missing from that module's pom.

### Deliberately out of scope

detekt 2.0 (alpha without an official Maven plugin; 1.23.8 parses Kotlin 2.4
sources as long as no 2.4-only syntax is used), Testcontainers 2 (we pin 1.21.4), the `List<@Valid ...>` change on
`CreatePaymentIntentRequestDTO.splits` (needs the Kotlin type-annotation
emission flag), the `deploy-infra.yml` / `destroy-infra.yml` action bumps.
Each is its own small PR when wanted.

## Working habits that made the steps cheap

- **Read the migration guide once, then let the compiler drive.** Boot's
  migration guide lists hundreds of changes. Only those that break our build
  matter. `mvn clean install -DskipITs` after the version bump produced the
  complete to-do list in one run.
- **Keep the migrator and warnings at zero inside the step.** A step ends with
  0 deprecation warnings and 0 migrator warnings. That is how the next step
  starts from a known state.
- **Pin only what Boot's BOM does not manage.** lz4-java, PostgreSQL,
  mybatis starter, springmockk, mockk. Everything else inherits from the
  parent and moves with it.
- **Prune `.trivyignore` with an empty ignore file.** Running Trivy with an
  empty ignore shows which suppressions still fire. After each version bump
  the list shrinks; what remains gets a comment naming the version that fixes it.
- **Test output to files, build output on the console.** Failsafe writes
  `target/failsafe-reports/<Test>-output.txt`; the HTML report comes from
  `mvn -pl <module> surefire-report:failsafe-report-only`.

## References

- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot 4.1 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)
- [Spring Framework 7.0 upgrade notes](https://github.com/spring-projects/spring-framework/wiki/Upgrading-to-Spring-Framework-7.x)
