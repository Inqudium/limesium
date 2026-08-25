# Architecture & Appropriateness Analysis: limesium-servlet-logging

> **Status: ✅ REMEDIATED (2026-08-22).** Finding 1 was withdrawn as a documented decision (below); the
> Low findings 2 to 6 were fixed in commit `9a6fc90a` — per-finding resolution in
> [section 8](#8-remediation-status-2026-08-22).
>
> **Post-review update (2026-08-22).** Finding 1 (predecessor not retired) is **withdrawn as a finding**:
> `./common-web/README.md` now records the succession — the inbound logging families there are superseded
> by the two endpoint-logging twins and frozen (no new features, no new hosts), names the two features
> that remain only there, and gives the migration steps; this module's README points to it. The
> statistics and the checklist below are amended accordingly; see
> [section 7](#7-post-review-update-2026-08-22). Everything else describes the state at the analyzed
> commit and is retained unchanged.

1. Identification of the Codebase
   - **Repository:** `https://github.com/dhaase/tool-box.git`
   - **Commit-Hash:** `0520fc61f5b1c9c8a6f71db12667375e1be9de68` (Full)
   - **Reference (Branch/Tag):** `refs/heads/main` / Tag: none
   - **Working-tree qualification:** clean at analysis start; this report is the analysis's only write.
2. Scope of the Analysis
   - **Included (production):** `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/` (15 files, 2,078 lines), `./limesium-servlet-logging/src/main/resources/`, `./limesium-servlet-logging/pom.xml`, `./limesium-servlet-logging/README.md`, `./limesium-servlet-logging/docs/endpoint-logging-reference.yml`, `./limesium-servlet-logging/docs/elk/`
   - **Included (test code — full depth):** `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/` (15 files, 3,348 lines, 109 `@Test` methods). Test code IS an analysis subject: section (B) "test architecture as an appropriateness subject" applies in full depth, in addition to the always-applied reading of testability as an architecture signal (A).
   - **Consulted read-only for the baseline (not analysis subjects):** `./limesium-reactive-logging/` (the twin; its architecture review of the same day, `./limesium-reactive-logging/docs/assessment/ARCHITECTURE_REVIEW-2026-08-22T17-38-54.md`, and its README decision record), `./common-web/src/main/kotlin/com/ing/dl/common/web/filter/servlet/logging/` (the predecessor this module succeeds), `./CLAUDE.md`, the three existing defect analyses under `./limesium-servlet-logging/docs/assessment/` (verified against the current code before any use)
   - **Excluded:** Maven output (`./limesium-servlet-logging/target/`), `./.git/`, all other reactor modules
3. Analysis Environment & Tools
   - **Target Environment:** Java 21, Kotlin 2.4.10, Spring Boot 4.1.0, Jakarta Servlet 6.1 (blocking stack; Tomcat is the test-time container, the module is a library)
   - **Build system:** Apache Maven 3.9.15 (multi-module reactor, `spring-boot-starter-parent` BOM)
   - **Analysis tools used:** complete manual source review in Phase-1 rank order; `grep`/`wc`/`diff`; Git metadata. No SonarQube/detekt/ArchUnit configured or run; ktlint and JaCoCo are configured at the parent. A full `mvn verify` of the module at the preceding commit (109 tests green, ktlint clean) was observed earlier in the same session and is cited as evidence only.
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/tool-box` (absolute reference point; all relative paths refer to it)
   - **Report output path:** `./limesium-servlet-logging/docs/assessment/ARCHITECTURE_REVIEW-2026-08-22T18-12-19.md`
   - **Scope root (relative to the workdir):** `./limesium-servlet-logging/`
   - **Path convention for findings:** `<path relative to the workdir>:<line>`

## 1. Executive Summary

The module is the reference implementation of a narrow concern — one structured `endpoint_*` log line per servlet HTTP exchange, the exchange identity in the MDC while the request runs, five meters — and its structure is proportionate to that concern. The heavy parts are all **load-bearing** and each is tied to a failure mode that was observed, not imagined: the emission at `requestDestroyed` (the only moment the container's error-dispatched status is final), the request-attribute handoff plus open-exchange gauge that make a missed destruction observable, the passive tee wrappers that reproduce the Servlet stream/reader and `reset()` contracts, the two async mechanisms (a Servlet `AsyncListener` for disposition, a Spring MVC `CallableProcessingInterceptor` for worker-thread MDC) which answer two different gaps, and the fail-open containment that is layered because the defect analyses found each layer's hole in turn. Classic over-engineering is absent: no single-implementation interface exists (the two `fun interface`s are the project's injected time/randomness principle, with hand-written test implementations), no pass-through layer, no pattern decoration; `RequestLoggingFilter` stays one final class with collaborators instead of the predecessor's template-method hierarchy, and the 20-parameter constructor of that predecessor became a properties class with a 119-line commented reference. Under-engineering was not found either: the domain logic (header selection and masking, level/outcome classification, capture accounting, path matching) is pure or near-pure and unit-tested without a container.

The twin duplication with `limesium-reactive-logging` — eleven same-named files, ~1,050 identical lines — is, as of today, a **documented decision** in both READMEs with rationale, residual cost, and revisit triggers; by this analysis's own rules it is a justifying force and is therefore recorded as structure, not as a finding. The one Medium finding sits at the repository level rather than inside the module: the predecessor `LoggingFilter` family in `common-web` (~2,200 lines) still exists alongside its declared successor, without a documented retirement or migration path, so the reactor carries two implementations of the same concern. The Low findings are the servlet-side mirrors of what the twin review found (implicit flag-based `Exchange` state, per-constant anonymous enum subclasses, two compile dependencies broader than needed), plus a mirrored Spring constant string and one test-seam observation.

**Test verdict.** (1) *Testability of the architecture:* good — time and ids injected, the filter drives against `MockHttpServletRequest/Response` with a hand-invoked destruction listener, the emitter/metrics/capture are POJOs behind constructor injection, the auto-configuration is exercised with `WebApplicationContextRunner`; only container ordering (error dispatch, async completion, Boot's observation-filter order) needs a real Tomcat, which is exactly what the two `@SpringBootTest` classes cover. (2) *Utilization:* healthy and matched to (1): 109 tests — 11 real-Tomcat, 6 context-runner, ~92 isolated unit tests; no mocking library, no sleeps, one 33-line `AwaitingAppender` as the only shared helper; failure injection is done through the servlet objects the filter touches (throwing header enumeration, lying async state, a throwing meter registry), which is the hand-written-fake discipline CLAUDE.md prescribes. (3) *Most significant gaps & anomalies:*
- the emitter's level/outcome matrix is verified only through the filter-plus-listener handshake although the emitter is an injectable seam of its own — wasted, not missing, potential (finding 6);
- the async state is a set of independent volatile flags whose legal combinations are argued in comments, not modeled (finding 2);
- the lockstep contract with the twin is pinned on the *reactive* side (its tests read this module's files); this module's own `TwinContractTest` pins only literals — behavioural drift inside the identical emitter/metrics code is covered by neither, as the README now states.

## 2. Problem baseline & methodology

**Core domain.** Inbound-exchange observability for blocking Spring Boot applications: correlation-id adoption/echo, one completion event with a fixed field family, optional arrival line, bounded body capture and size measurement, header selection/masking, trace join from the tracing bridge's MDC, and the module's own fail-open telemetry. Twelve of fifteen production files serve exactly that; the other three are the injectable time/id interfaces and a seven-line fail-open helper.

**Real requirements & scale.** The filter is in every request's hot path of a host; the requirement is "zero request impact, bounded memory per exchange", not throughput of its own. Domain complexity is moderate and lifecycle-shaped: when is the status final (after the container's ERROR dispatch), what does the Servlet API guarantee about async and about wrappers (`startAsync()` drops them, `reset()` invalidates accessors), which thread carries which MDC. Team context: one maintainer, strict testing conventions (no mocks, injected time, Given/When/Then), high operational maturity (ELK component template, documented meters, three defect analyses with remediation tables within two days).

**Documented architectural intent.** No ADR directory exists in the reactor. Intent is carried by the README (successor of `common-web`'s `LoggingFilter` family, redesign not port; the reactive twin and the deliberate duplication, recorded today), by CLAUDE.md's principles (injectable time, no `synchronized`, functional decoration, no mocks), and by unusually dense KDoc that names the analysis finding each design move answers. These are treated as justifying forces; where code deviates from them, it would be a finding — none was found.

**Technology coherence.** Coherent blocking stack: `spring-web` + `jakarta.servlet-api` (provided) + Micrometer at compile scope; Tomcat, MVC, and the Brave tracing bridge only at test scope. No reactive types, no coroutines, no locks, no `synchronized` (CLAUDE.md's virtual-thread constraint holds); cross-thread visibility is `@Volatile`/`AtomicBoolean`, appropriate for a single-writer, single-late-reader handoff. The module avoids a `spring-webmvc` dependency by mirroring one attribute-name constant (finding 5) and by reaching MVC's async manager through `spring-web`'s `WebAsyncUtils`.

**Test topology observed.** 15 test files / 109 tests: 2 real-Tomcat `@SpringBootTest` classes (11 tests), 1 context-runner class (6 tests), 12 pure unit classes (~92 tests) driving Spring's servlet mocks and Logback's `ListAppender`, plus the two lockstep tests against this module's own `docs/` files. Infrastructure per typical test: none. Global state touched: none beyond per-test Logback appenders (restored) and the pooled-thread MDC (restored).

**Analyzed vs. not analyzed.** Every production and test file was read in full. The twin and the predecessor were read only as far as needed for the consistency question. **Blind spots:** no profiling (none needed for the findings); host-side dependency cost inferred from the POM; container behavior beyond the Servlet 6.1 contract taken from the prior defect analysis.

## 3. Statistics

| Severity | Findings |
|---|---:|
| Critical | 0 |
| High | 0 |
| Medium | 0 (finding 1 withdrawn after the review — see section 7) |
| Low | 5 |
| **Total** | **5** (6 as reported; finding 1 withdrawn) |

**Systemic patterns detected:** 1 (per-constant anonymous enum subclasses for a type assertion, replicated across three modules). The twin duplication is recorded as documented structure, not as a pattern of mismatch — see section 6.

## 4. Ranking table

| Unit | Score | Rationale |
|---|---:|---|
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt` | 5 | Owns wiring, chain, nested fail-open guards, async hooks, the destruction handoff and the listener — the unit a mismatch would radiate from |
| `CapturingRequestWrapper.kt` / `CapturingResponseWrapper.kt` | 4 | Re-implement Servlet stream/reader/writer state machines over a tee; the densest contract surface in the module |
| `Exchange.kt` (+ `AsyncOutcomeMarker`) | 4 | Cross-thread state as six `@Volatile` fields plus a CAS; carries servlet handles and wrappers |
| Twin-duplicated contract layer: `EndpointLogFields.kt`, `RequestLoggingProperties.kt`, `EndpointLoggingMetrics.kt`, `MdcKeys.kt`, `ExchangeLogEmitter.kt` | 3 | Identical to the twin by documented decision; judged here only for its own shape |
| `EndpointMdcCallableInterceptor.kt` / `RequestLoggingAutoConfiguration.kt` | 3 | Second MDC mechanism; load-bearing filter order (`+ 10`) pinned against Boot internals |
| `BoundedBodyCapture.kt` | 2 | Small, single-writer, volatile-published |
| Lockstep/contract tests: `EndpointLogFieldTest.kt`, `EndpointLoggingReferenceConfigTest.kt`, `TwinContractTest.kt` | 3 | The alignment mechanism of the twin design, owned on this side |
| Integration tests: `RequestLoggingFilterIntegrationTest.kt`, `RequestLoggingFilterTracingIntegrationTest.kt` | 2 | Real Tomcat, 11 tests, three tracing test dependencies for two tests that pin a convention |
| Unit tests (remaining 11 classes) | 1 | Plain JUnit/AssertJ, servlet mocks, no framework building |
| `NanoTimeSource.kt`, `CorrelationIdGenerator.kt`, `FailOpenDiagnostics.kt` | 1 | Pure, single-purpose |
| `pom.xml`, `README.md`, `docs/` | 2 | Dependency scopes; the reference YAML and ELK template are the cross-stack contract |

## 5. Findings

### 🔴 Critical

No Critical findings.

### 🟠 High

No High findings.

### 🟡 Medium

- [x] 1. **Withdrawn — documented decision, see section 7.** [`./limesium-servlet-logging/README.md:6` (declares the succession); predecessor at `./common-web/src/main/kotlin/com/ing/dl/common/web/filter/servlet/logging/AbstractLoggingFilter.kt`] {Medium → no finding} {Confidence: medium} {Consistency} The declared predecessor is not retired: two implementations of servlet exchange logging coexist in the reactor without a documented migration or end-of-life path
  - Actual structure: the README positions this module as the successor of the ~2,200-line `LoggingFilter` family in `common-web` and lists what it deliberately leaves out (body masking transformers, per-key response sampling — "use the predecessor if you need them"). `common-web` remains a reactor module in `./pom.xml`; nothing in the reactor depends on `limesium-servlet-logging`, so the only evidence of the succession is prose.
  - Solved problem / justifying force: the redesign itself is well justified (the README's table names the concrete defects of the predecessor's design). Keeping the predecessor *during* migration is reasonable; keeping it *indefinitely* with an explicit pointer to it for two features is a decision that is not documented as such — neither a sunset, nor a plan to port the two features, nor a statement that both modules are supported.
  - Cost: a host choosing between two overlapping modules, two implementations of the same field family to keep dashboards aligned with, and defect-analysis/remediation effort that reaches only the successor (three analyses here, none of the predecessor). The cost is repository-level and outside this module's code, which is why confidence is medium — the maintainer may already regard `common-web` as frozen legacy, in which case one sentence would settle it.
  - Simpler alternative: decide and write it down in this README — either "predecessor frozen, no new hosts, removal after the last migration" or "both supported, these two features stay there" — and, if the former, port or drop the two out-of-scope features so the pointer can go. Strategy only.
  - Reversibility: documentation-only in the minimal form; the porting variant is a contained feature addition.

### 🟢 Low

- [x] 2. [`./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/Exchange.kt:37`] {Low} {Confidence: high} {Boundaries & Responsibilities} The exchange lifecycle is an implicit state machine over six `@Volatile` fields and one CAS (`logged`, `failure`, `pathTemplate`, `asyncStarted`, `timedOut`, `asyncErrored`, `asyncFailure`), with the precedence rules ("timeout beats a later onError", "callback-true, never throwable-inferred") living in the emitter's `when` and in comments. Correct and well documented, and simpler than the twin's seven-flag variant (no commit deferral here); the cost is purely cognitive. A single atomic disposition (`COMPLETED | TIMED_OUT | ERRORED`, set once by the marker) would make the precedence a data property rather than a comment. Local change, low priority; mirror of the twin review's finding 4.

- [x] 3. [`./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/EndpointLogFields.kt:42`] {Low} {Confidence: high} {Pattern Overuse} Each of the 14 enum constants overrides an abstract `format` solely to call `checked<T>()` — 14 anonymous subclasses whose only variation is a type argument. The guarantee (a field owns its wire shape) is sound and documented; a constructor parameter with one non-abstract `format` gives the same guarantee in 14 one-line constants. Replicated in the twin and in web-client's `AdapterLogFields` (systemic pattern 1) — change consciously in all three or in none.

- [x] 4. [`./limesium-servlet-logging/pom.xml:19`, `:42`] {Low} {Confidence: high} {Dependency/build appropriateness} `spring-boot-starter` (full starter with logging and YAML transitives) and `kotlin-reflect` (no reflective use in `src/main`; Boot's Kotlin binding needs it on the host, which every Kotlin Boot host already has) are compile-scope dependencies of a library that needs `spring-boot-autoconfigure`/`spring-boot` for `@ConfigurationProperties` and `@AutoConfiguration`. Near-zero cost today (all hosts are Boot applications with the same transitives); a two-line narrowing. Mirror of the twin review's finding 6.

- [x] 5. [`./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:354`] {Low} {Confidence: medium} {Coupling & Cohesion} `BEST_MATCHING_PATTERN_ATTRIBUTE` mirrors `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` as a string literal to avoid a `spring-webmvc` dependency; the same trade-off appears in the twin for WebFlux. The force is real and documented (the module must work in a non-MVC servlet application), and Spring has not renamed the constant in a decade, so the drift risk is small — but it is silent: a rename would make `endpoint_url_template` disappear without any test failing, because the integration tests assert the *value* MVC sets, and a renamed constant would make MVC set it under the new name. An `optional`/`provided` `spring-webmvc` dependency used only for the constant would turn the drift into a compile error at zero host cost; whether that is worth a dependency line is a judgment call — hence Low.

- [x] 6. [`./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterTest.kt:172` (the "Level escalation" group, representative)] {Low} {Confidence: medium} {Testability & Test Architecture} The level/outcome matrix, the slow escalation, and the body/header rendering are verified exclusively through the filter-plus-destruction-listener handshake (`doFilterInternal` followed by a hand-fired `requestDestroyed`), although `ExchangeLogEmitter` is an injectable seam that could be driven with a hand-built `Exchange`. Not a missing proof — every branch is reached — but each classification test pays the filter's wiring (correlation, captures, MDC scope, async registration) to test a pure decision, and a future emitter change will be diagnosed through filter-shaped failures. Using the seam for the classification tests would be a small, additive change; the existing tests stay as the end-to-end layer.

## 6. Systemic patterns

1. **Per-constant anonymous enum subclasses for a type assertion — 3 modules × 14 constants (counting basis: `override fun format` occurrences in `EndpointLogFields.kt` of both twins and `AdapterLogFields.kt` in web-client).** Consolidated in finding 3.

**Recorded as documented structure, not as a mismatch:** the twin duplication — 11 same-named files, ~1,050 identical lines with `limesium-reactive-logging` (counting basis: `diff` unchanged-line count per file, measured in the twin's review). The decision to keep the twins standalone, its rationale (one twin per host, no third artifact for two consumers, rarely changing contract layer) and its accepted residual cost (every shared-layer change is a port in both directions; the literal pins catch named-contract drift only) are documented in this module's README ("The reactive twin — deliberate duplication, no shared base module") and in the twin's. By this analysis's discipline a documented decision with a stated cost is a justifying force; the revisit triggers named there (a third stack, or port frequency stops being occasional) are the right ones.

**Noted, deliberately not a finding:** the fail-open containment is layered at nine catch sites (`try`/`catch` → counter + internal log → `reportQuietly` → fallback meter registry), and the filter's `doFilterInternal` nests three guarded regions. Heavy for a logging filter, but every layer answers a failure mode a defect analysis constructed, and "never affect the request" is the module's defining requirement — load-bearing by the yardstick applied here. Likewise the two async mechanisms (`AsyncOutcomeMarker` for disposition via the Servlet API, `EndpointMdcCallableInterceptor` for worker-thread MDC via Spring MVC): different gaps, different APIs, no overlap.

## 7. Post-review update (2026-08-22)

**Finding 1 withdrawn.** The finding asked for a decision to be written down, and it was: `./common-web/README.md` (new) states that the servlet `LoggingFilter` family and the reactive `LoggingWebFilter` are superseded by `limesium-servlet-logging` and `limesium-reactive-logging` and are frozen — no new features, no new hosts, maintenance limited to keeping the reactor building; it tables the module's packages with their status (superseded / still used by the WebClient connector / not covered by the twins), names the two features without a successor (per-key interval response sampling, body masking transformers) with the rule that a further need is ported into the twins rather than extended here, and gives the four migration steps. `./limesium-servlet-logging/README.md` no longer says "use the predecessor if you need them, or extend here" but points to that status. The cost factor of the finding — a host choosing between two overlapping modules without guidance — is thereby removed; the remaining residual (two implementations exist until the last host has migrated) is the documented, bounded state of a frozen predecessor.

**Consequences for the remaining findings.** None — the Low findings 2 to 6 are unaffected.

## 8. Remediation status (2026-08-22)

Findings 2 to 6 were fixed in commit `9a6fc90a`; both twins verified green afterwards (`mvn -pl limesium-servlet-logging,limesium-reactive-logging verify`: 118 and 97 tests, ktlint clean).

| Finding | Resolution |
|---|---|
| 2 — implicit async state over two flags | **Fixed.** `Exchange.asyncDisposition: AsyncDisposition` (`NONE`/`TIMED_OUT`/`ERRORED`) replaces `timedOut`/`asyncErrored`; `AsyncOutcomeMarker` sets it with the precedence built in (`TIMED_OUT` always wins, `ERRORED` only from `NONE`), and the emitter's `when` reads one value. Pinned in `ExchangeLogEmitterTest` (timeout then onError → still `timeout`; onError without throwable → `failure`). |
| 3 — per-constant anonymous enum subclasses | **Fixed** in both twins. `EndpointLogField` takes its wire type as a `KClass` constructor parameter; one non-abstract `format` asserts it. 13 one-line constants instead of 13 anonymous classes; the existing `EndpointLogFieldTest` type-guarantee tests pass unchanged. web-client's `AdapterLogField` was left as is: its enum has a second shape (`checkedOrNull` for optional fields) and is outside this module's scope. |
| 4 — library dependencies broader than needed | **Fixed** in both twins. `spring-boot-autoconfigure` + an explicit `slf4j-api` (used directly) replace `spring-boot-starter`; `kotlin-reflect` is no longer declared per module (the parent POM supplies it to every module, so the declaration was redundant rather than removable from the host's view). |
| 5 — mirrored Spring constant, silent drift | **Fixed** in both twins. `HandlerMappingAttributeTest` asserts the mirrored string against `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` of Spring MVC / WebFlux (on the test classpath); a rename now fails the build. The production code keeps its literal — the Spring constant is computed from the class name and cannot be inlined, so referencing it would reintroduce the runtime dependency the mirror avoids. |
| 6 — emitter seam unused by the classification tests | **Fixed.** `ExchangeLogEmitterTest` drives the level/outcome matrix, slow escalation, exactly-once and the level gate through the emitter's own seam with a hand-built `Exchange`; the filter-level tests remain as the end-to-end layer. |
