# Fixing the detekt baseline

How to work through the detekt findings, module by module, until the baselines are empty.
For what detekt is and when it runs, see [code-quality.md](code-quality.md).

## What the baseline is

Each module has a `detekt-baseline.xml`: **every finding still left in that module's code**, in all files
(`src/main` and `src/test`), whatever their age. It is a to-do list, not a list of accepted problems:

- The findings in it are **tolerated for now**, so the build stays green while they're being fixed step by step.
- Anything **not** in it fails `mvn verify`: code you write or change must be clean.
- The goal is an **empty** baseline. Progress per module and per step is tracked in
  [code-health/checkup-2026-10-04.md](code-health/checkup-2026-10-04.md) (table at the top).

One line per finding:

```xml
<ID>LongParameterList:InternalTransfer.kt$InternalTransfer.Companion$( transferId: InternalTransferId, ... )</ID>
<ID>ArgumentListWrapping:Account.kt$AccountType.PLATFORM_CASH$(NormalBalance.DEBIT, AccountCategory.ASSET)</ID>
```

`<rule>:<file name>$<where in the file>$<the code it is about>`. There are no line numbers, so a finding stays
matched when lines above it move.

But the **code** is part of the id: when you change that code (rename a parameter, add one, reformat the line),
the old line no longer matches, and the finding counts as **new**. E.g. renaming `merchantAccountId` to
`merchantAccount` in `InternalTransfer` turns its `LongParameterList` baseline entry into a new finding. So
touching old code usually means fixing its findings too: that's intended.

## 1. See what's left

Count per module:
```bash
for f in */detekt-baseline.xml; do echo "$(grep -c '<ID>' "$f") $f"; done | sort -rn
```

Count per rule in one module:
```bash
grep -o '<ID>[A-Za-z]*' payment-domain/detekt-baseline.xml | sed 's/<ID>//' | sort | uniq -c | sort -rn
```

Every finding with file and line, ignoring the baseline (`-Ddetekt.baselineFile=` switches it off for this run):
```bash
mvn -B -o -pl payment-domain detekt:check -Ddetekt.baselineFile=
```
Output: `.../MerchantAccount.kt:4:1: The import 'java.util.UUID' is unused. [UnusedImports]`. The build fails
at the end; that's expected, it only lists.

## 2. Clean up one module

Work one module at a time (smallest dependencies first: `common`, `payment-domain`, `common-test`,
`payment-application`, ...). Start from a clean state: commit or set aside your other work first, so the diff
shows only the cleanup.

### a. Let detekt fix the formatting
```bash
m=payment-domain
# 1. every file must end with a newline: without it, detekt's auto-fix crashes on that file
#    ("The source location line must be greater than 0") and writes nothing
for f in $(find $m/src -name '*.kt'); do [ -s "$f" ] && [ -n "$(tail -c1 "$f")" ] && echo >> "$f"; done
# 2. auto-fix; pass the files as a list: with a folder as input, detekt fixes nothing
files=$(find $PWD/$m/src -name '*.kt' | paste -sd, -)
mvn -B -o -q -pl $m detekt:check -Ddetekt.autoCorrect=true -Ddetekt.baselineFile= -Ddetekt.input="$files"
```
Fixes wrapping, spacing, blank lines, import order and final newlines in **every file of the module**. It ends
in `BUILD FAILURE` because it reports what it found; the fixes are written anyway. Only layout changes, no
behaviour: `git diff -w` (ignore whitespace) should show nothing new. Look over the diff in IntelliJ's Commit
window.

### b. Fix the rest by hand
List what's left (same command as in step 1) and work through it. Per rule:

| Rule | Fix |
|---|---|
| `UnusedImports`, `UnusedPrivateMember`, `UnusedPrivateProperty`, `UnusedParameter` | delete it (check it's really unused: IntelliJ greys it out too) |
| `InvalidPackageDeclaration` | the `package` line doesn't match the folder: move the file (IntelliJ: F6 Move) or fix the `package` line |
| `MaxLineLength` (over 120 characters) | break the line where it reads best, e.g. one argument per line. Break only at a space, `+` or comma: never inside a name, a number or a `${…}` template. A long string: split it into `"…" +` parts at a space. A one-line `/** … */`: make it a multi-line KDoc. A fully qualified class name in code: import it instead |
| `MagicNumber` | give the number a name: a `const val` in a `companion object`, e.g. `SIMULATED_PSP_FEE_BPS = 150L` |
| `UseCheckOrError`, `UseRequire` | in `payment-domain`: our `require(x) { PaymentDomainException.… }` from `domain/model/common/Preconditions.kt` (import it explicitly, or Kotlin's own `require` is used silently); elsewhere `throw IllegalStateException("...")` → `error("...")`, `if (!x) throw IllegalStateException(...)` → `check(x) { "..." }`. Messages name the payment (`paymentId=…`). See [code-health/exception-hierarchy.md](code-health/exception-hierarchy.md) |
| `TooGenericExceptionCaught` | root `CLAUDE.md` §5: catch only where you can do something useful. Then: **narrow** to the real type (`DataAccessException`, `IOException`, `JsonProcessingException`, `ExecutionException`, `KafkaException`); **log and rethrow** → remove the catch (the handling layer logs once, e.g. the Kafka recoverer); **count a metric and rethrow** → `try/finally` with a success flag, no catch; **react per subtype** → catch the sealed family once, exhaustive `when (e) { is A, is B -> … }` |
| `SwallowedException` | log it **with** the exception where it's handled, or let it propagate; if dropping it is the point (a timeout that is the expected outcome, "not a UUID" = invalid), keep it and suppress (below) |
| `LongParameterList` | first check the callers: if several arguments come from one object they already have (`payment.paymentId`, `payment.paymentIntentId`, `captureTx.txId`, …), pass that object instead (e.g. `Tx.createSettleTx(txId, captureTx, …)`). Don't invent a new class to group arguments. If the size is natural (an aggregate's constructor / `rehydrate` with all its fields, Spring `@Value` config), keep it |
| `LongMethod`, `TooManyFunctions`, `ReturnCount`, `ThrowsCount` | refactor if it makes the code clearer; if the size is natural, keep it and suppress (below) |

### c. Suppressing a finding on purpose
When a finding is not a problem in that place, say so in the code, with the reason, instead of keeping it in
the baseline:
```kotlin
@Suppress("LongParameterList") // rehydrate takes every persisted field of the aggregate
fun rehydrate(...)
```
For a single `catch`, put it on the caught parameter, so it covers only that one catch:
```kotlin
} catch (@Suppress("SwallowedException") e: TimeoutException) { // the timeout IS the answer
```
The suppression is visible in review and stays with the code; a baseline entry is invisible.

### d. Run the tests
```bash
mvn clean verify
```
A cleanup must not change behaviour. If you touched e2e-relevant code, also
`mvn clean verify -f e2e-tests/pom.xml -Ddocker.client.api.version=1.44`.

### e. Regenerate the module's baseline
```bash
mvn -B -o -pl payment-domain detekt:create-baseline
```
The baseline now holds only what you deliberately left. Check its diff: it should only **lose** lines. A line
that was **added** is a finding you introduced: fix it instead.

### f. Commit
One commit per module (or per rule, for a big module), code and `detekt-baseline.xml` together, e.g.
`detekt cleanup: payment-domain`.

## Rules

- **Never regenerate a baseline to make a new finding go away.** The baseline only shrinks. Regenerating is
  for after a cleanup (step 2e), never instead of a fix.
- **Auto-fix rewrites whole files.** `-Ddetekt.autoCorrect=true` formats every finding in the files it is given,
  including those in the baseline. With `infra/scripts/detekt-changed.sh` that's only your changed files; with
  the module command above, it's the whole module.
- **Keep cleanups separate from features.** A cleanup commit changes layout and small things only, so it's easy
  to review and easy to revert.

## Done when

```bash
for f in */detekt-baseline.xml; do echo "$(grep -c '<ID>' "$f") $f"; done
```
shows 0 everywhere (or only entries you decided to keep, better turned into `@Suppress` with a reason). Then
the baseline files can be deleted and the `<baseline>` line removed from the root `pom.xml`.
