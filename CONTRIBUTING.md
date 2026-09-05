# Contributing to Limesium

Thank you for considering a contribution! This document explains how to get set up,
what the project expects from changes, and how to submit them.

## Ground rules

- Be respectful; the [Code of Conduct](CODE_OF_CONDUCT.md) applies to all project spaces.
- For anything larger than a trivial fix, please **open an issue first** and discuss the
  change before writing code. This avoids wasted work if the change doesn't fit the
  project's scope.
- Security issues must **not** be reported as public issues — see [SECURITY.md](SECURITY.md).

## Project scope

Limesium does one thing: emit exactly one structured `endpoint_*` log line per HTTP
exchange at the service's own boundary, via two paradigm twins (servlet and reactive)
with **identical fields and identical configuration**. Contributions that widen this
scope (metrics frameworks, tracing backends, other protocols) are likely out of scope —
ask first.

The twin symmetry is a hard invariant: a new field or configuration property must land
in **both** modules, with the shared contract in
`docs/endpoint-logging-reference.yml` updated and the
contract tests passing in both.

## Development setup

Prerequisites:

- JDK 24+ to BUILD (CI uses 25; `.mvn/jvm.config` passes flags a pre-24 JVM
  rejects) - the published artifacts still target Java 21
- Maven 3.9+ (or use your IDE's bundled Maven)

Build and test everything:

```
mvn verify
```

This compiles both modules, runs all tests, and runs the `ktlint` style check.
A PR must pass `mvn verify` cleanly. Test coverage (JaCoCo) is collected in the
same run and written per module to `target/site/jacoco/`; skip it locally with
`-Djacoco.skip=true` if you need a faster loop.

### Dependency vulnerability scan

CI additionally scans the **resolved** dependency graph against the
[OSV](https://osv.dev/) database and fails on any known advisory. It runs on
every push and pull request, and weekly — a newly published advisory has to
surface even when nothing was committed. To reproduce it locally (requires a
container runtime):

```bash
mvn cyclonedx:makeAggregateBom        # SBOM of the resolved graph → target/bom.json
docker run --rm -v "$PWD:/repo" \
  ghcr.io/google/osv-scanner-action:v2.5.1 --lockfile=/repo/target/bom.json
```

The scan uses an SBOM rather than `pom.xml` because most versions come from
the Spring Boot BOM and never appear in `pom.xml`; test-scoped dependencies
are excluded, since they reach no consumer.

When an advisory appears, prefer fixing it — usually a version pin in
`<dependencyManagement>` with a rationale comment, even when the affected
artifact is transitive. Only if an advisory is genuinely unfixable *and*
provably not exploitable here, record it in an `osv-scanner.toml` with the
reason and the date it was assessed — do not remove the gate.

### Static analysis (CodeQL)

The dependency scan covers *published advisories in dependencies*; the
`CodeQL` workflow covers the complementary half — this project's own code
(`java-kotlin`) and the workflow definitions themselves (`actions`). It runs
on every push and pull request and weekly, and results land in the
repository's **Security → Code scanning** tab rather than in the build log.
A finding there is triaged like a review comment: fix it, or dismiss it in
the UI with a written reason.

### Code style

- Kotlin code is checked with **ktlint** (via `ktlint-maven-plugin`, bound to `verify`).
  Run `mvn ktlint:format` to auto-format before committing.
- Match the surrounding code's comment density and naming; comments should state
  constraints the code can't express, not narrate the code.
- **One normative source per contract statement** (architecture review of
  2026-09-05, finding 2): property semantics live in
  `docs/endpoint-logging-reference.yml` (the reactive module's own file carries
  only `variant`), shared behaviour in the common guide, per-stack behaviour in
  the module guide, decisions in the ADRs. Everywhere else - READMEs, KDoc, the
  other guides, code comments - name the key or link the section; do not restate
  the rule. A rule that exists twice drifts twice. Boundary for the shared PUBLIC
  types (`HeaderLogProperties`, `BodyLogMode`, `MaskingKey`; comment audit round 2
  of 2026-09-05, CA-11): their KDoc is the normative source of the TYPE's contract -
  what the lists mean for `select`, what a mode means for `logs`, what construction
  rejects - because it is the only text a host reads in the API reference; the
  reference YAML names those keys and defaults and points to the type for the rule.

### Tests

- Every behavior change needs a test in the module it touches.
- Changes to the shared field/configuration contract need the reference file and the
  contract tests in **both** modules updated.
- Test classes follow the existing `*Test.kt` naming (Surefire picks up `**/*Test.kt`).
- Log output is observed through the shared `CapturedLogger` JUnit extension
  (`@JvmField @RegisterExtension val exchangeLog = CapturedLogger(name)`, from
  `limesium-common`'s test-jar - `events`, `awaitEvents(n)`, `logger` for level
  changes) and the `ILoggingEvent.keyValues()` extension; do not re-create the
  Logback attach/detach fixture per class.
- Test rationale comments follow the existing three-line pattern at the top of the
  test body (`What is tested:` / `Success criteria:` / `Why it matters:`), followed
  by the `Given/When/Then` stage comments. The
  [test-evidence page](https://inqudium.github.io/limesium/tests/test-evidence/) and
  the [coverage reports](https://inqudium.github.io/limesium/coverage/) on the docs
  site are GENERATED by the Docs workflow from the Surefire and JaCoCo output
  (`.github/scripts/`); never edit `docs/tests/` by hand or check it in. Your
  test's rationale comment is what appears there — another reason to keep the
  pattern intact.

### Fuzzing (Jazzer `@FuzzTest`)

The components that parse or bound caller-controlled data - body capture,
header masking, the `traceparent` parser - are fuzzed with
[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)'s JUnit 5
integration: `*FuzzTest.java` classes under `src/test/java`, stating their
invariants in the test body. They run in two modes:

- **Regression mode, in every build.** `mvn verify` executes each fuzz test
  against its checked-in inputs (`src/test/resources/**/<Class>Inputs/`)
  plus the empty input - cheap, deterministic, part of the normal suite.
  Every target ships a small SEED corpus there (conformant and malformed
  headers, masking edge cases, byte sequences at the capture cap); without
  one, regression mode would execute exactly one empty input and prove
  nothing. `FuzzedDataProvider` consumes strings and byte arrays from the
  FRONT of an input and booleans/integers from its END, so a seed carries
  the interesting text first and a short trailer of control bytes last.
- **Fuzzing mode, nightly.** The `Fuzz` workflow sets `JAZZER_FUZZ=1` and
  runs each target in its own job (Jazzer fuzzes only one `@FuzzTest` per
  JVM), each capped by its `@FuzzTest(maxDuration = ...)`.

A finding is written into the seed-corpus directory next to the test
sources (the nightly run also uploads it as a workflow artifact): commit it
there and it becomes a permanent regression input; then fix the code. The
nightly run also uploads the corpus it grew (`.cifuzz-corpus/`, ignored by
git) as an artifact - promote an input from it into the seed directory when
it reaches a branch the seeds do not. New
parsing/bounding surface should bring a fuzz target stating its invariants,
like the existing ones do - in JAVA, not Kotlin: the OpenSSF Scorecard
fuzzing detector only recognizes Jazzer in `*.java` files.

To fuzz locally (no Docker needed):

```bash
JAZZER_FUZZ=1 mvn -Dtest=TraceparentFuzzTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Submitting changes

1. Fork the repository and create a topic branch from `main`.
2. Make your change with tests; keep commits focused and messages descriptive.
3. Ensure `mvn verify` passes.
4. Open a pull request against `main` describing **what** changed and **why**.
   Link the issue it addresses, if any.

By submitting a contribution you agree that it is licensed under the
[Apache License 2.0](LICENSE), the project's license, and you certify the
[Developer Certificate of Origin](https://developercertificate.org/) — i.e. you have
the right to submit the work under that license.

## Reporting bugs

Please include:

- Limesium version and module (`limesium-servlet-logging` or `limesium-reactive-logging`)
- Spring Boot version and stack (Tomcat / Netty, Reactor / coroutines)
- Relevant configuration (`endpoint-logging.*` properties)
- What you expected, what happened, and a minimal reproduction if possible
