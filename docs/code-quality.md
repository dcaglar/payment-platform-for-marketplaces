# Code quality and security checks

Three free, open-source tools check the code. Each one has a **baseline**: a file listing the findings that are
still in the code. They are tolerated for now so the build stays green, and are being fixed step by step; it's a
to-do list, not a list of accepted problems. **Any finding not in the baseline fails.** Fix one, then remove it
from the baseline: the baselines only shrink.

| Tool | Checks | Runs in | Baseline |
|---|---|---|---|
| **detekt** | Kotlin code: unused imports / private code, package not matching the folder, complexity, formatting | `mvn verify` (local + CI) | `<module>/detekt-baseline.xml` |
| **gitleaks** | secrets (keys, tokens, passwords) in the git history and in uncommitted changes | `infra/scripts/security-scan.sh` (local + CI) | `.gitleaksignore` |
| **Trivy** | HIGH/CRITICAL vulnerabilities in dependencies (with a fix available); misconfigurations in Dockerfiles, Helm charts, Terraform | `infra/scripts/security-scan.sh` (local + CI) | `.trivyignore` |

The whole flow (what runs when, in which order, existing vs new findings) as a diagram:
[`diagrams/build-and-quality-checks.excalidraw`](diagrams/build-and-quality-checks.excalidraw), open it at
excalidraw.com (Open → choose the file).

---

## detekt (Kotlin code)

### When it runs
- `mvn clean verify` (and `install`): the last step of every module, after the tests. Also for e2e-tests
  (`mvn verify -f e2e-tests/pom.xml`).
- **Not** in `mvn clean test`.
- A new finding fails the build of that module and lists file, line and rule, e.g.
  `MerchantAccount.kt:4:1: The import 'java.util.UUID' is unused. [UnusedImports]`.

### Configuration
- Rules: detekt's defaults plus the overrides in [`config/detekt/detekt.yml`](../config/detekt/detekt.yml).
  Only list a rule there to change it.
- Formatting rules come from ktlint (`detekt-formatting`). Where detekt and ktlint have the same rule, the ktlint
  copy is switched off so a finding isn't reported twice.
- Plugin setup: root [`pom.xml`](../pom.xml) (`detekt-maven-plugin`). The `no-sources` profile turns detekt off
  for modules without Kotlin (the root aggregator pom).

### Check or fix only your local changes
[`infra/scripts/detekt-changed.sh`](../infra/scripts/detekt-changed.sh) runs detekt on only the Kotlin files you
changed or added (uncommitted), module by module. Committed files are not touched.

```bash
infra/scripts/detekt-changed.sh            # fix what detekt can fix itself (wrapping, spacing, import order), then list what's left
infra/scripts/detekt-changed.sh --check    # only list the findings, change nothing
```

What is left after fixing (long lines, unused code, too many parameters, ...) needs a human. A module with
findings left ends in `BUILD FAILURE`; that's expected, the fixes are written to the files anyway.

### Other detekt commands
```bash
mvn -pl payment-domain detekt:check    # check one module (its baseline applies)
```
Don't run `detekt:check` without `-pl` from the root: the root pom has no sources and no baseline, so it fails
there. `mvn verify` handles that by itself.

### Working through the baseline
To fix the findings in the baselines (see them, fix, regenerate the baseline, track progress), see
[fixing-detekt-baseline.md](fixing-detekt-baseline.md).

### IntelliJ plugin (optional)
Settings → Plugins → Marketplace → **detekt** → Install. Then Settings → Tools → detekt:
- ✅ Enable detekt, ✅ Build upon default config, ✅ Enable formatting (ktlint) rules
- Configuration file: `config/detekt/detekt.yml`

Findings are underlined while you type; right-click → **Refactor → AutoCorrect by detekt rules** fixes the
formatting of a file. The plugin takes a single baseline file, so it may also show findings that are in the
module's baseline (still to fix, but not failing the build). `mvn verify` decides what fails.

---

## Security scan (gitleaks + Trivy)

### Run it
```bash
infra/scripts/security-scan.sh
```
Needs Docker only (the tools run as containers, nothing to install). It runs three checks and ends with
`✅ No new findings` or `❌ New findings` (exit code 1):

1. **gitleaks, git history:** every commit. Secrets are printed redacted.
2. **gitleaks, uncommitted changes:** what you're about to commit.
3. **Trivy:** dependencies from the poms and `package-lock.json` files, plus Dockerfiles, Helm charts and
   Terraform. Only HIGH and CRITICAL, only where a fixed version exists.

Run `mvn install` first: Trivy reads the dependencies from your local Maven cache (`~/.m2`) and stays offline.
Letting it download every pom from Maven Central gets your IP rate-limited (`429 Too Many Requests`).

### A new finding
- **A real secret:** remove it from the code, and **rotate it** at the provider (once pushed, assume it's
  public: the repo is public). Removing it from the latest commit doesn't remove it from the history.
- **A false positive** (e.g. a test value): add its fingerprint (printed by gitleaks, `commit:file:rule:line`)
  to `.gitleaksignore`.
- **A vulnerable dependency:** upgrade it. Only if there's no way to upgrade yet, add the CVE id to
  `.trivyignore` with a comment why.

### Cleaning up old findings
- `.trivyignore`: most entries come from Spring Boot 3.5.0; upgrading it fixes them. Then delete their lines.
- `.gitleaksignore`: these are in old commits and stay in the history. The Stripe keys among them are test-mode
  keys (`sk_test_`); roll them in the Stripe dashboard.

---

## CI (`.github/workflows/ci.yml`)

On a push to `feature/**`, `fix/**`, `hotfix/**` and on a PR to `main`:

| Job | Runs | Includes |
|---|---|---|
| `unit-tests` | `mvn clean test` | unit tests |
| `security-scan` (in parallel) | `infra/scripts/security-scan.sh` | gitleaks + Trivy |
| `integration-tests` (after unit-tests) | `mvn clean verify` | integration tests + **detekt** |
| `e2e-acceptance` (after integration-tests, PR only) | `mvn verify -f e2e-tests/pom.xml` | e2e tests + detekt |

## GitHub settings (free for public repos, switch on once)
Settings → Code security:
- **Dependabot alerts** and **Dependabot security updates**: PRs that bump vulnerable dependencies.
- **Secret scanning** and **Push protection**: GitHub blocks a push that contains a known secret format.

## Not covered
- **Unused public functions / classes across modules:** detekt only sees private code. Use IntelliJ:
  Code → Inspect Code → "Unused declaration".
- **Spring-specific checks:** no good free tool for Spring in Kotlin.
