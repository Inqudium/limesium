# Architecture & Appropriateness Analysis: limesium-reactive-logging

> **Status: ✅ REMEDIATED (2026-08-22).** Finding 1 was withdrawn as a documented decision (below); findings
> 5 and 6 were fixed together with the servlet twin's review (commit `9a6fc90a`), findings 2, 3, 4 and 7 in
> commit `426366fb` — per-finding resolution in [section 8](#8-remediation-status-2026-08-22).
>
> **Post-review update (2026-08-22).** Finding 1 (twin duplication) was discussed with the maintainer
> after this analysis and is **withdrawn as a finding**: the decision to keep the twins standalone is now
> documented in both modules' READMEs (commits `01a7a660`, `bfa8d588`) with its rationale and its
> accepted residual cost — a documented decision is a justifying force by this analysis's own rules. The
> statistics and the checklist below are amended accordingly; see [section 7](#7-post-review-update-2026-08-22).
> Everything else describes the state at the analyzed commit and is retained unchanged.

1. Identification of the Codebase
   - **Repository:** `https://github.com/dhaase/tool-box.git`
   - **Commit-Hash:** `5ee579a048fb7cb331aea3cc8838c24e9a085cfb` (Full)
   - **Reference (Branch/Tag):** `refs/heads/main` / Tag: none
   - **Working-tree qualification:** clean at analysis start (no tracked or untracked change below `./limesium-reactive-logging/`); this report is the analysis's only write.
2. Scope of the Analysis
   - **Included (production):** `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/` (18 files, 1,928 lines), `./limesium-reactive-logging/src/main/resources/`, `./limesium-reactive-logging/pom.xml`, `./limesium-reactive-logging/README.md`
   - **Included (test code — full depth):** `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/` (16 files, 3,207 lines, 79 `@Test` methods). Test code IS an analysis subject: section (B) "test architecture as an appropriateness subject" applies in full depth, in addition to the always-applied reading of testability as an architecture signal (A).
   - **Consulted read-only for the baseline (not analysis subjects):** the servlet twin `./limesium-servlet-logging/src/main/kotlin/` (for line-identity comparison), `./web-client/src/main/kotlin/com/ing/agreementpreference/adapter/web/ExchangeDiaryLogging.kt` (third copy of shared routines), `./CLAUDE.md`, the three existing defect analyses under `./limesium-reactive-logging/docs/assessment/` (verified against the current code before any use)
   - **Excluded:** Maven output (`./limesium-reactive-logging/target/`), `./.git/`, all other reactor modules
3. Analysis Environment & Tools
   - **Target Environment:** Java 21, Kotlin 2.4.10, Spring Boot 4.1.0 (WebFlux/Reactor 3.8 line; the module is a library, the reactive runtime comes from the host)
   - **Build system:** Apache Maven 3.9.15 (multi-module reactor, `spring-boot-starter-parent` BOM)
   - **Analysis tools used:** complete manual source review in Phase-1 rank order; `grep`/`wc`/`diff` (line-identity diff between the twins as the duplication measure); Git metadata. No SonarQube/detekt/ArchUnit configured or run; ktlint and JaCoCo are configured at the parent. A full `mvn verify` of the module (96 tests green, ktlint clean) was observed earlier in the same session at this commit and is cited as evidence only, not re-run for this analysis.
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/tool-box` (absolute reference point; all relative paths refer to it)
   - **Report output path:** `./limesium-reactive-logging/docs/assessment/ARCHITECTURE_REVIEW-2026-08-22T17-38-54.md`
   - **Scope root (relative to the workdir):** `./limesium-reactive-logging/`
   - **Path convention for findings:** `<path relative to the workdir>:<line>`

## 1. Executive Summary

The module is a small, single-purpose Spring Boot library (one `WebFilter`, one structured log line per HTTP exchange, five meters) whose complexity is almost entirely **load-bearing**: the exactly-once completion guard, the commit-deferred error emission, the frozen body capture, and the layered fail-open containment each answer a concrete, documented failure mode that the two preceding defect analyses surfaced — and the contract "a logging component must never affect the request it describes" justifies defense in depth at every catch site. The reactive stack itself is not a choice of this module but of its hosts; the module fits that stack idiomatically (decorator tee on `DataBuffer`s, Reactor context plus Micrometer accessors for MDC parity, a `CoWebFilter` variant for coroutine hosts). Over-engineering in the classic sense — speculative interfaces, pass-through layers, pattern decoration — is essentially absent: the two `fun interface`s are the project's injected-time/randomness principle, the single marker interface `EndpointLoggingFilter` is the mechanism by which two real implementations back each other off, and `ExchangeLifecycle` is a genuine extraction from two concrete callers.

The one structural mismatch with real running cost sits **between** this module and its servlet twin: eleven of the eighteen production files are twins of files in `limesium-servlet-logging`, ~1,050 lines are byte-identical (seven files ≥ 85 % identical), the web-client carries a third copy of two routines, and the only thing keeping the copies aligned is a set of literal-pin tests plus two tests that read the sibling module's working tree through a relative filesystem path. That design is documented as deliberate ("standalone by design, no cross-module dependency") and the safety net works — but every fix is now a port, which the commit history of this very day demonstrates four times over. The remaining findings are Low: an implicit seven-flag state machine on `Exchange`, a classpath-presence switch for the coroutine variant, per-constant anonymous enum subclasses that only type-check, two library dependencies broader than the module needs, and a reactive contract verified blocking throughout. Under-engineering in the sense of missing boundaries or logic interwoven with I/O was not found; the domain logic (header selection, masking, traceparent parsing, level/outcome classification, the capture) is pure or near-pure and unit-tested without a container.

**Test verdict.** (1) *Testability of the architecture:* good — time and ids are injected, the filter runs against `MockServerWebExchange` without a Spring context, the capture and the parsers are POJOs, and the auto-configuration is exercised with context runners; the only seam that requires a real container is the Netty buffer/commit behavior, which is exactly what the two `@SpringBootTest` classes cover. (2) *Utilization:* healthy pyramid and well matched to (1): 79 tests, of which 10 are real-Netty integration tests, 16 are context-runner tests, and the remaining ~53 are isolated unit tests; no mocking library, no sleeps, no home-grown test framework (one 33-line `AwaitingAppender`). (3) *Most significant gaps & anomalies:*
- two lockstep tests couple this module's build to the **sibling module's working tree** via `../limesium-servlet-logging/...` paths rather than a declared artifact (finding 2);
- the **reactive contract is verified blocking** throughout (43 `.block()` calls, `reactor-test` declared but `StepVerifier` unused) — a coherence gap, not a missing proof, since cancellation and deferral are exercised via `subscribe().dispose()` and real Netty (finding 7);
- the implicit `Exchange` state machine's interleavings are proven only by reasoning in comments, not by an explicit state model a test could enumerate (finding 4).

## 2. Problem baseline & methodology

**Core domain.** One concern: emit one structured `endpoint_*` log event per inbound HTTP exchange in a WebFlux application — with the same message format, field family, configuration namespace, and meters as the servlet twin, so that dashboards and index mappings are stack-agnostic. Secondary concerns that follow from it: correlation-id adoption/echo, bounded body capture, header selection/masking, trace-context join via `traceparent`, handler-side MDC parity, and fail-open operation including its own observability (fail-open counters, open-exchange gauge).

**Real requirements & scale.** The module sits in the request hot path of every host application; its non-functional requirement is therefore "zero request impact, bounded memory" rather than throughput of its own (latency cost is one map-tee per `DataBuffer` and one log event per exchange). Domain complexity is moderate: the hard parts are lifecycle (terminal signal vs. commit, cancellation), not data. Team context: a single maintainer (CLAUDE.md), German-speaking, with a strict testing convention (no mock libraries, injected time, Given/When/Then). Operational maturity is high — ELK component templates, a documented metrics contract, three prior defect analyses with remediation tables.

**Documented architectural intent.** No ADR exists for the endpoint-logging modules (the reactor's `./docs/` holds deployment/capacity documents; `./docs/adr/` does not exist). The intent is carried by the READMEs and KDoc: the servlet module is the *reference implementation*; this module is its *deliberately duplicated twin*; "standalone by design, no cross-module dependency" (`./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/MdcKeys.kt:22`); classpath presence is the opt-in for both the coroutine variant and context propagation "so the `endpoint-logging.*` namespace stays identical". CLAUDE.md's project-wide principles (injectable time, no `synchronized`, no mocks, functional decoration) are followed. These documented intents are treated as justifying forces below; where the cost they incur is concrete and recurring, it is still named.

**Technology coherence.** Reactive throughout and coherent: `spring-web` + `reactor-core` at compile scope, Netty only in test scope, no blocking call in the production path (the capture lock is uncontended and microsecond-scale; `PrintWriter`-style blocking I/O does not exist here). The coroutine variant bridges via `CoWebFilter`/`MDCContext` without introducing a second paradigm into the shared lifecycle. Micrometer context propagation is used the way it is designed (JVM-global `ContextRegistry`).

**Test topology observed.** 16 test files / 79 tests: 2 real-Netty `@SpringBootTest` classes (10 tests, ~1.5 s total), 3 context-runner classes (16 tests), 11 pure unit classes (~53 tests) driving `MockServerWebExchange`, POJOs, or the Logback `ListAppender`. Infrastructure per typical test: none. Shared test helpers: one `AwaitingAppender` (33 lines). Global JVM state touched by tests: `ContextRegistry`, Reactor `Hooks`, and (reflectively) the SLF4J MDC adapter — each restored in `@AfterEach`.

**Analyzed vs. not analyzed.** Every production and test file was read in full. The servlet twin and web-client were read only as far as needed to measure duplication; they are not judged here. **Blind spots:** runtime behavior under load (no profiling was done, none was needed for the findings below); the host-side cost of the `spring-boot-starter` dependency was inferred from the POM, not measured; Reactor 3.8 operator internals were taken from the prior defect analysis, not re-derived.

## 3. Statistics

| Severity | Findings |
|---|---:|
| Critical | 0 |
| High | 0 |
| Medium | 1 (a second Medium, finding 1, was withdrawn after the review — see section 7) |
| Low | 5 |
| **Total** | **6** (7 as reported; finding 1 withdrawn) |

**Systemic patterns detected:** 2 (twin/triplet duplication with literal-pin tests as the alignment mechanism; per-constant anonymous enum subclasses for a type assertion, replicated across three modules).

## 4. Ranking table

| Unit | Score | Rationale |
|---|---:|---|
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/ExchangeLifecycle.kt` | 5 | Central extraction shared by both variants; the implicit state machine (terminal vs. commit, deferral, arming), fail-open wiring, and most catch sites live here — a mismatch would radiate into every exchange |
| Twin-duplicated contract layer: `EndpointLogFields.kt`, `RequestLoggingProperties.kt`, `EndpointLoggingMetrics.kt`, `MdcKeys.kt`, `ExchangeLogEmitter.kt` (`./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/`) | 4 | ≥ 85 % line-identical with the servlet twin; the module boundary runs *through* a shared concept; drift is held only by tests |
| `RequestLoggingWebFilter.kt` / `CoRequestLoggingWebFilter.kt` / `EndpointLoggingFilter.kt` | 4 | Two real variants behind one marker interface; paradigm bridge (Reactor ↔ coroutines); selection mechanics |
| `Exchange.kt` | 3 | Seven `@Volatile` fields plus two `AtomicBoolean`s form an implicit, cross-thread state machine |
| `RequestLoggingAutoConfiguration.kt` / `CoRequestLoggingAutoConfiguration.kt` / `EndpointMdcContextPropagation.kt` | 3 | Two auto-configurations with ordering-dependent back-off, classpath-presence switches, JVM-global registry mutation |
| `BoundedBodyCapture.kt` / `CapturingDecorators.kt` | 3 | Lock-guarded mutable capture and the `DataBuffer` tee — concurrency justified by the cancellation race, but a heavier unit than its size suggests |
| Lockstep tests: `EndpointLogFieldTest.kt`, `EndpointLoggingReferenceConfigTest.kt`, `TwinContractTest.kt` (`./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/`) | 3 | The alignment mechanism of the twin design; filesystem coupling across the reactor |
| Integration tests: `RequestLoggingWebFilterIntegrationTest.kt`, `RequestLoggingWebFilterReactorIntegrationTest.kt` | 2 | Real Netty, two classes, 10 tests — proportionate |
| `Traceparent.kt`, `FailOpenDiagnostics.kt`, `NanoTimeSource.kt`, `CorrelationIdGenerator.kt` | 1 | Small, pure, single-purpose |
| Unit tests (remaining 11 classes) | 1 | Plain JUnit/AssertJ, no framework building |
| `pom.xml`, `README.md`, auto-configuration imports | 2 | Dependency scope appropriateness |

## 5. Findings

### 🔴 Critical

No Critical findings.

### 🟠 High

No High findings.

### 🟡 Medium

- [x] 1. **Withdrawn — documented decision, see section 7.** [`./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/EndpointLogFields.kt:1` (representative; eleven files)] {Medium → no finding} {Confidence: high} {Coupling & Cohesion / Under-Engineering} The twin design duplicates a shared contract layer (~1,050 identical lines in 11 files, a third copy in web-client) and keeps it aligned only by tests and manual porting
  - Actual structure: of the 18 production files, 11 have a same-named counterpart in `./limesium-servlet-logging/`; measured by line identity (`diff`, unchanged lines): `EndpointLoggingMetrics.kt` 223/233, `EndpointLogFields.kt` 169/184, `RequestLoggingProperties.kt` 162/170, `ExchangeLogEmitter.kt` 190/267, `MdcKeys.kt` 78/89, `CorrelationIdGenerator.kt` 18/19, `NanoTimeSource.kt` 18/19, `FailOpenDiagnostics.kt` 18/20, plus partial twins (`Exchange.kt`, `BoundedBodyCapture.kt`, `RequestLoggingAutoConfiguration.kt`). `HeaderLogProperties.mask` and the `traceparent` parser additionally exist a third time in `./web-client/.../ExchangeDiaryLogging.kt`. Alignment is enforced by `TwinContractTest` (literal pins of meter names, MDC keys, mask format), by `EndpointLogFieldTest` and `EndpointLoggingReferenceConfigTest` reading the servlet module's files through relative paths, and by the maintainer porting fixes.
  - Solved problem / justifying force: documented intent — "standalone by design … no cross-module dependency" (`MdcKeys.kt:22`), the servlet module as reference implementation (README), identical configuration namespace without a shared artifact. The force is real (a host should be able to depend on one jar) but implicit in KDoc rather than an ADR, and the force argues against a *runtime* dependency on the *other twin* — it does not argue against a third, stack-neutral artifact both twins depend on.
  - Cost: every change to the shared layer is two (or three) edits and two test runs; the remediation commits of this day alone ported four changes across the twins (threshold comparison, `MdcScope` restore, meter fallback, `reportQuietly`). Reviewers must diff twins to know whether a divergence is intentional (`cancelled`/`timeout`) or drift. The lockstep tests catch *named* contract drift (meter names, field names, config keys) but not behavioral drift in the ~190 identical emitter lines or the 223 identical metrics lines — those diverge silently until the next port.
  - Simpler alternative: extract the stack-neutral contract — field enum, properties and header selection/masking, meters, MDC keys/scope, time and id interfaces, the event rendering of the emitter, fail-open helper — into one small library module both twins depend on; the twins keep only the stack-specific lifecycle, tee, and auto-configuration (roughly 700 lines each). The reference YAML and the ELK template would move with it, removing the filesystem coupling of finding 2. Strategy only; no module-cut diagram here.
  - Reversibility: the *current* state is cheap to leave standing as long as the literal pins are maintained; the extraction is mechanical (identical files) but touches published artifact coordinates, so it is a deliberate, one-time restructuring rather than a refactor — which is why this is Medium and not High.

- [x] 2. [`./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/EndpointLoggingReferenceConfigTest.kt:24`; `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/EndpointLogFieldTest.kt:27`] {Medium} {Confidence: high} {Testability & Test Architecture} Two contract tests depend on the sibling module's working tree through relative filesystem paths instead of a declared build dependency
  - Actual structure: `FileSystemResource("../limesium-servlet-logging/docs/endpoint-logging-reference.yml")` and `Path.of("../limesium-servlet-logging/docs/elk/…component-template.json")` — the test assumes the Maven module directory layout of this checkout and the presence of an *unbuilt* sibling directory.
  - Solved problem / justifying force: the one-template/one-reference lockstep across stacks is a real and valuable contract; reading the file is the simplest way to assert it without a shared artifact (which is the consequence of finding 1).
  - Cost: the module cannot be tested from a sparse checkout, a moved module, or a CI job that builds the module in isolation from its artifact; the dependency is invisible to Maven (`mvn -pl limesium-reactive-logging` works only because the sibling directory happens to exist). The failure mode is a file-not-found in a test that reads as a contract violation.
  - Simpler alternative: publish the reference YAML and the ELK template as test resources of a shared artifact (naturally the module of finding 1, or a tiny `endpoint-logging-contract` test-jar) and read them from the classpath. Until then, at minimum locate the files via a system property set by the Maven build rather than a hard-coded `..` path.
  - Reversibility: trivial once a shared artifact exists; otherwise a small build-configuration change.

### 🟢 Low

- [x] 3. [`./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/CoRequestLoggingAutoConfiguration.kt:30`] {Low} {Confidence: medium} {Consistency / Configurability} The filter variant is selected by classpath *presence* of two optional libraries, not by an explicit switch. The force is documented (identical `endpoint-logging.*` namespace across twins) and the output is identical by construction, so the cost is small: a Reactor-only host that pulls `kotlinx-coroutines-reactor` transitively runs the `CoWebFilter` variant without knowing it, and the integration-test classpath of this very module demonstrates the effect (the shipped selection had to be overridden by `spring.autoconfigure.exclude` to test the reference variant). A single documented property (`endpoint-logging.variant`) would make the choice visible without breaking namespace parity — the servlet twin would simply not bind it.

- [x] 4. [`./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/Exchange.kt:34`] {Low} {Confidence: high} {Boundaries & Responsibilities} The exchange lifecycle is an implicit state machine spread over seven `@Volatile` fields and two `AtomicBoolean` guards (`completed`, `logged`, `failure`, `cancelled`, `awaitingCommit`, `commitCallbackArmed`, `committedStatus`, `pathTemplate`); its legal interleavings are argued in comments (`ExchangeLifecycle.kt:171-181`) rather than modeled. The concurrency is real (terminal signal vs. commit callback) and correctly handled, so this is not a defect; the cost is cognitive — a reader reconstructs the state diagram from the flag combinations — and one guard is redundant by construction (`logged` is only ever reached through `complete()`, which already won the `completed` CAS). A single atomic state (`OPEN → AWAITING_COMMIT → COMPLETED`) with the flags folded in would make the interleavings enumerable and testable; low priority, local change.

- [x] 5. [`./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/EndpointLogFields.kt:42`] {Low} {Confidence: high} {Pattern Overuse} Each of the 14 enum constants overrides an abstract `format` only to call `checked<T>()` — 14 anonymous subclasses (14 class files) whose sole variation is a type argument. The rationale (a field owns its wire shape; call sites cannot bypass it) is sound and documented; the *mechanism* is heavier than the benefit: a constructor parameter (`KClass<*>` or a small sealed shape) with one non-abstract `format` achieves the same guarantee in 14 one-line constants. Replicated identically in the servlet twin and in web-client's `AdapterLogFields` (see systemic pattern 2), so a change should be made in one place — which again points to finding 1.

- [x] 6. [`./limesium-reactive-logging/pom.xml:17`, `:63`] {Low} {Confidence: high} {Dependency/build appropriateness} Two compile-scope dependencies are broader than the module uses: `spring-boot-starter` (pulls the logging starter, SnakeYAML, and `spring-boot-starter` transitives into every host — a library needs `spring-boot-autoconfigure` plus `spring-boot` for `@ConfigurationProperties`) and `kotlin-reflect` (no reflective use exists in `src/main`; Boot's Kotlin constructor binding needs it on the *host's* classpath, which every Kotlin Boot host already has). Cost today is near zero because all hosts are Boot applications with identical transitives; it matters the day a host pins different versions. Narrowing the scopes is a two-line change.

- [x] 7. [`./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilterTest.kt:203` (representative; 43 `.block()` calls across the unit tests)] {Low} {Confidence: medium} {Testability & Test Architecture} The reactive contract is verified blocking throughout: `.block()` drives every Reactor-variant unit test, `reactor-test` is declared in the POM but `StepVerifier` is never used. The evidence is not *missing* — cancellation is exercised via `subscribe().dispose()`, the commit race via `setComplete().block()`, and both variants run against real Netty — so this is a coherence observation rather than a gap: signal ordering, context propagation through `contextWrite`, and the `Mono.defer` guarantee are asserted by their side effects, not by the stream's signals. Either use `StepVerifier` for the handful of signal-ordering tests or drop the unused dependency; both are small.

## 6. Systemic patterns

1. **Twin/triplet duplication held together by literal-pin tests — 11 files, ~1,050 identical lines (counting basis: `diff` unchanged-line count per same-named file across `./limesium-reactive-logging/` and `./limesium-servlet-logging/`; third copies of `mask` and the `traceparent` parser in `./web-client/`).** Representative: `EndpointLoggingMetrics.kt` (96 % identical), `EndpointLogFields.kt` (92 %), `RequestLoggingProperties.kt` (95 %), `ExchangeLogEmitter.kt` (71 %). The alignment mechanism — `TwinContractTest` in both modules plus two cross-reactor file reads — is itself a pattern worth naming: it is the test suite doing the job a shared artifact would do. Consolidated in findings 1 and 2.
2. **Per-constant anonymous enum subclasses for a type assertion — 3 modules × 14 constants (counting basis: `override fun format` occurrences in `EndpointLogFields.kt` of both twins and `AdapterLogFields.kt` in web-client).** Consolidated in finding 5.

**Noted, deliberately not a finding:** the fail-open containment is layered four deep at seven catch sites (`try`/`catch` → counter + internal log → `reportQuietly` → fallback meter registry). This is heavy for a logging filter, but each layer answers a failure mode that was observed or constructed in a prior defect analysis, and the contract "never affect the request" is the module's defining requirement — load-bearing complexity by the yardstick of this analysis. Likewise the two filter variants: the coroutine variant exists because `MDCContext` restoration on coroutine resumption is not obtainable from Reactor's automatic propagation alone, and both variants share one `ExchangeLifecycle` — a genuine second implementation, not speculative generality.

## 7. Post-review update (2026-08-22)

**Finding 1 withdrawn.** The maintainer's response to the twin-duplication finding rests on two facts the analysis had weighted too lightly: a host application is either a servlet or a reactive application, so the two copies never share a classpath and there is no runtime drift to guard against; and the shared layer is contract-level code whose change frequency, once the remediation rounds are over, is expected to be occasional — at which point porting by hand is cheaper than a third artifact with its own versioning and release coupling, for exactly two consumers. The analysis accepts this: the cost factor named in finding 1 (every change is a port) is real but bounded by frequency, and with two consumers a base module sits at, not beyond, the rule-of-three threshold.

What turns the finding into a non-finding is that the decision is now **documented where a reviewer finds it**, with rationale and residual cost, in both twins:

- `./limesium-reactive-logging/README.md` — "Deliberate duplication — why there is no shared base module" (commit `01a7a660`)
- `./limesium-servlet-logging/README.md` — "The reactive twin — deliberate duplication, no shared base module" (commit `bfa8d588`)

Both name the revisit triggers (a third stack, or a port frequency that stops being occasional) and the residual risk the lockstep tests do not cover (behavioural drift inside the identical emitter and metrics code, as opposed to named-contract drift).

**Consequences for the remaining findings.** Finding 2 (lockstep tests read the sibling module's working tree through relative paths) stands on its own and is unchanged — a base module was only one of its possible remedies; the others (test-resource copies verified against the original, or a build-supplied path) remain open. Finding 5's remark that the enum pattern should be changed "in one place" now reads as: change it in both twins and web-client, consciously. Systemic pattern 1 is retained as a description of the structure, no longer as a mismatch.

## 8. Remediation status (2026-08-22)

Findings 2 to 7 are fixed; the module verified green afterwards (`mvn -pl limesium-reactive-logging verify`: 99 tests, ktlint clean).

| Finding | Resolution |
|---|---|
| 2 — lockstep tests read the sibling's working tree via `../` paths | **Fixed** (`426366fb`). The servlet twin's `docs/endpoint-logging-reference.yml` and `docs/elk/…component-template.json` are declared as a test resource of this module in the POM; `EndpointLoggingReferenceConfigTest` and `EndpointLogFieldTest` read them through `ClassPathResource`. The dependency on the sibling checkout remains — it is the documented twin decision — but it is now declared in the build and fails at resource processing with a clear message, not inside a test as an apparent contract violation. |
| 3 — variant selected by classpath presence only | **Fixed** (`426366fb`). Reactive-only key `endpoint-logging.variant` = `auto` (default, classpath-based as before) / `reactor` (the coroutine auto-configuration backs off via a `NoneNestedConditions` although its libraries are present) / `coroutine` (the Reactor auto-configuration refuses its fallback with a message naming the missing libraries). Pinned by two auto-configuration tests; documented in the README. The servlet twin does not bind the key; the rest of the namespace stays identical. |
| 4 — implicit seven-flag state machine, redundant `logged` guard | **Fixed** (`426366fb`). `Exchange.state: AtomicReference<ExchangeState>` (`OPEN → AWAITING_COMMIT → COMPLETED`) replaces `completed`, `logged` and `awaitingCommit`; `complete()` is the single transition to `COMPLETED` and the emitter's second CAS is removed. The remaining volatile fields (`failure`, `cancelled`, `pathTemplate`, `committedStatus`, `commitCallbackArmed`) are data, not state. |
| 5 — per-constant anonymous enum subclasses | **Fixed** (`9a6fc90a`, with the servlet review; web-client and sync-bridge followed in `f140c57b`). `EndpointLogField` takes its wire type as a `KClass` constructor parameter; one non-abstract `format` asserts it. |
| 6 — library dependencies broader than needed | **Fixed** (`9a6fc90a`). `spring-boot-autoconfigure` + explicit `slf4j-api` replace `spring-boot-starter`; `kotlin-reflect` is no longer declared per module (the parent supplies it). |
| 7 — reactive contract verified blocking, `StepVerifier` unused | **Fixed** (`426366fb`). The Reactor-variant signal tests verify the signals as such: `verifyComplete()` for the line-format test, `expectErrorSatisfies` for the commit-deferred error (the error signal passes unchanged, nothing logged before it), `thenCancel().verify()` for the client disconnect. The remaining `.block()` calls drive side-effect assertions where the signal itself is not the subject; `reactor-test` is now used. |
