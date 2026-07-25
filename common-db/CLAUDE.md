# common-db — domain⇄DB mapping kernel (shared library, NOT deployable)

A shared **library**, not a runnable service. It has no `main`, no `@Mapper` XML, no Liquibase changelogs of its own — it is compiled INTO the actually-deployed modules (`payment-service`, `payment-consumers`, `payment-edge-workers`, `payment-central-relay`), which supply the datasource, mappers, and migrations. Its single job: translate between **domain entities** and **DB POJO entities**, plus provide the custom MyBatis type handlers.

## DDD dependency direction (the point of this module)
- **Domain drives infrastructure.** The dependency arrow points inward: `common-db` depends on `payment-domain`, never the reverse. The domain aggregate is the source of truth for shape and rules; the DB representation is a downstream projection of it.
- Therefore **the only way to change what gets persisted is to change the domain entity first**, then reflect it in the POJO + mapper here. Never model a new field on the POJO and let it leak upward — that inverts the arrow.
- This module is the *anti-corruption seam*: MyBatis/Postgres concerns stop here so the domain stays framework-free.

## Two representations it bridges
- **Domain entity** = rich aggregate (`PaymentIntent`, `Payment`, `JournalEntry`, …) with VOs, behavior, invariants (in `payment-domain`).
- **DB POJO entity** (`entity/…Entity.kt`) = flat row mirror, **primitive types ONLY** — `Long`, `String`, `Instant`, and JSON-as-`String`. Value objects are flattened: e.g. `PaymentIntentEntity` has `totalAmountValue: Long` + `currency: String` (the `Amount` VO split apart), ids as raw `Long`, splits as `splitsJson: String`. No behavior, no domain types, no nullability beyond the column's.
- **`converter/…EntityMapper.kt`** = the bridge, the ONLY place the two meet: `toDomain(entity)` → the aggregate's `rehydrate(...)` (trusts persisted data, no re-validation); `toEntity(domain, …json)` → flatten back to the POJO.

## Custom type handlers + auto-config
- `typehandler/…TypeHandler.kt` are custom `BaseTypeHandler<T>` implementations (`InstantTypeHandler`, `UUIDTypeHandler`, `IdempotencyStatusTypeHandler`) that teach MyBatis how to read/write columns whose Kotlin type isn't a plain JDBC primitive.
- `CommonDbAutoConfiguration` is an `@AutoConfiguration` exposing each handler as a `@Bean`, so any consuming module gets them **registered automatically** just by depending on this library — services don't re-declare them. New non-primitive column type ⇒ add a `BaseTypeHandler` here and a `@Bean` there.

## The splits ⇄ lazy bridge (know before editing PaymentIntent/Payment mappers)
- `PaymentIntentEntityMapper.toDomain` builds `lazy { objectMapper.readValue(entity.splitsJson) }` and hands it to `PaymentIntent.rehydrate` — splits parse only on first `.splits` access, not at load. `toEntity` serializes them back. `splits_json` is write-once at insert. (See payment-domain/CLAUDE.md.)

## Rules
- Mappers must NOT enforce business invariants (that's `createNew`, not `rehydrate`). Don't re-check balances/currencies here.
- POJO entities stay primitive-only and behavior-free. Need logic? You want the domain aggregate, not the entity.
- `AbstractOutboxPartitionCreator` (outbox partition mgmt), `LiquibaseJobExiter` (clean exit for one-shot migration jobs), `EdgeWatermarkEntity` (edge→central store-and-forward watermark) also live here.
