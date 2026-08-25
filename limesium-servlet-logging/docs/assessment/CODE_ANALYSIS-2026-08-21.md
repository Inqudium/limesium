# Code & Defect Analysis: limesium-servlet-logging

> **Status: ✅ REMEDIATED (2026-08-21).** All 14 findings are resolved — fixed, verified against a real
> container, or documented as an accepted limitation — in commit
> `e6094cc5bc8f4434e683cf8979e5bb4dbb04b666`; the per-finding resolution is tabled in
> [section 7](#7-remediation-status-2026-08-21). Everything below this banner describes the state **at
> the analyzed commit** `14ad7f4b86e14429154e15b8533be21a8445b607` and is retained unchanged as the
> audit record — the executive summary and the findings deliberately still read in the present tense of
> that commit.

1. Identification of the codebase
   - **Repository:** `https://github.com/dhaase/tool-box.git`
   - **Commit hash:** `14ad7f4b86e14429154e15b8533be21a8445b607` (full)
   - **Reference (branch/tag):** `refs/heads/main`
2. Scope of the analysis
   - **Included:** `./limesium-servlet-logging/src/main/kotlin/` and `./limesium-servlet-logging/src/test/kotlin/` (relative to the workdir). **Test code is part of the analysis** — the test-specific heuristics apply, and test classes are analysis subjects in their own right.
   - **Excluded:** build output (`./limesium-servlet-logging/target/`), documentation (`./limesium-servlet-logging/docs/` — consulted as context, not analyzed for defects), all sibling modules (referenced only where this module's contracts point at them: `web-client`, `logback-kafka-appender`).
3. Analysis environment & tools
   - **Target environment:** OpenJDK 21, Kotlin 2.4.10, Spring Boot 4.1.0 (Spring Framework 7.0.8), servlet stack (Spring MVC, blocking; no WebFlux, no coroutines in this module)
   - **Build system:** Maven 3.9.15 (multi-module reactor, `spring-boot-starter-parent` 4.1.0)
   - **Analysis tools used:** manual review (full read of every main-source file, targeted read of every test class), `grep`-based sweeps; ktlint (via the parent build) is the only static analysis in place — no detekt/SpotBugs/Sonar, which raises the weight of manual review. No new build/test execution was performed for this analysis (read-only); the suite is known green at this commit (74 tests, `mvn verify` including ktlint) from runs earlier on the same working tree.
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/tool-box` (absolute reference point; all relative paths refer to it)
   - **Report output path:** `./limesium-servlet-logging/docs/assessment/CODE_ANALYSIS.md` (relative to the workdir)
   - **Scope root (relative to the workdir):** `./limesium-servlet-logging/`
   - **Path convention for finding locations:** `<path relative to the workdir>:<line>` (e.g. `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:71`)

---

## 1. Executive summary

The module is small (13 main files, ~1.4 kLOC), young, and unusually well-documented: every non-obvious decision (emission at request destruction, level/outcome decoupling, fail-open, tee-instead-of-cache, the filter-order dependency of the trace capture) is written down next to the code, and most of these decisions are pinned by tests. No Critical finding was identified; the single High finding is a **contract gap, not a crash in normal operation**: the class KDoc and README promise that logging failures never disturb request processing, but the entire pre-chain section of the filter (correlation resolution against a host-provided generator, header selection, capture construction, metrics calls) runs *before* the guarded region — an exception there fails the request with a 500, which is precisely what the fail-open contract rules out. The same guard-starts-too-late shape recurs in the emitter, where the section before the level gate (injected time source, status read, body-size recording) can throw past the fail-open catch, losing the event *without* incrementing the `emission` fail-open counter that exists for exactly this blind spot. The remaining findings are genuine but bounded edge cases: the response tee cannot see `reset()`/`resetBuffer()` and then logs discarded bytes; memory visibility of the capture buffers across the async-worker → destruction-thread handoff rests on container-internal happens-before edges rather than the module's own synchronization; and the correlation echo header is plausibly wiped by the container's error dispatch — untested, and the one place where an operator-facing promise lacks a proof.

**Test verdict.** (1) *Reliability as a safety net:* high. The suite is deterministic by construction (injected `AtomicLong` time, pinned id generator, no mock library, event-driven awaits via semaphore instead of sleeps), asserts exact values rather than shapes, and pins the module's convention dependencies (Boot's filter ordering and observation scoping) against a real Tomcat and a real Brave bridge — the class of test that turns silent degradation into build breakage. Deliberately-introduced defects in the emission, masking, tee, or metric paths would be caught. (2) *Pyramid:* healthy — fast unit tests carry the load (≈56 of 74), with two focused real-container integration classes on top; no flaky constructs (`Thread.sleep`, real time, order dependence) were found. (3) *Most significant gaps:*
- the client-visible **error path is under-asserted**: the 500 integration test checks the event but neither the correlation echo header nor response headers (ties to finding 5);
- the **`InterruptedException` fail-open branches are untested** in all three guards;
- the **`reset()`/`resetBuffer()` interaction** of the response tee has no test at all;
- one test helper (`RequestLoggingFilterBodyAndHeaderTest.handle`) skips request destruction on exceptions, unlike its sibling helpers — currently harmless, latently brittle.

## 2. Scope & methodology

- **Analyzed:** all production sources under `./limesium-servlet-logging/src/main/kotlin/` (13 files) — read completely; all test sources under `./limesium-servlet-logging/src/test/kotlin/` (12 files) — read completely, as analysis subjects (see verdict above). The reference YAML and the ELK component template were read as contract artifacts.
- **Not analyzed:** sibling modules; the `docs/` prose (used as the yardstick for "is the deviation the finding"); generated/build output.
- **Stack detected:** Spring Boot 4.1.0 on Spring Framework 7.0.8, blocking Spring MVC servlet stack; Micrometer 1.17 (core; tracing only test-scoped); Kotlin 2.4.10 / Java 21; no coroutines, no WebFlux, no persistence, no messaging in this module. Servlet API is `provided`; Tomcat appears only in tests.
- **Static analysis:** ktlint (style only) via the parent POM. No bug-pattern tool — noted as a data point; the manual pass was correspondingly deep.
- **Local verification:** none performed within this read-only analysis. Suite state at this commit is known green from prior runs on the identical tree (74 tests, `mvn verify`).
- **Known limitations / blind spots:** container-internal behavior (Tomcat's `response.reset()` during error dispatch, happens-before edges of the async state machine, `requestDestroyed` firing guarantees) was assessed from the servlet specification and implementation knowledge, not from a Tomcat source audit — findings 4 and 5 carry reduced confidence for that reason and say so.

## 3. Statistics

| Severity | Count |
|---|---|
| 🔴 Critical | 0 |
| 🟠 High | 1 |
| 🟡 Medium | 5 |
| 🟢 Low | 8 |
| **Total** | **14** |
| Systemic patterns | 2 |

## 4. File ranking (Phase 1)

Production code:

| File (relative to `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/`) | Score | Rationale |
|---|---|---|
| `RequestLoggingFilter.kt` | 5 | Entry point; servlet lifecycle, wiring of captures/MDC/metrics/listeners; the fail-open boundary lives here |
| `ExchangeLogEmitter.kt` | 5 | Emission on a foreign callback thread; level/outcome branching; fail-open guards; reads shared exchange state |
| `BoundedBodyCapture.kt` | 4 | Cross-thread mutable state with documented (not self-enforced) visibility assumptions |
| `CapturingResponseWrapper.kt` | 4 | Stream/writer tee, charset resolution, interaction with container buffer semantics |
| `CapturingRequestWrapper.kt` | 3 | Read-side tee; reader/stream either-or; charset fallback |
| `EndpointLoggingMetrics.kt` | 3 | Meter registration semantics, dynamic `uri` tags, gauge backing state |
| `RequestLoggingProperties.kt` (incl. `HeaderLogProperties`) | 3 | Selection/masking logic, wildcard semantics, validation |
| `Exchange.kt` | 2 | State record; `@Volatile` discipline; marker listener |
| `RequestLoggingAutoConfiguration.kt` | 2 | Bean wiring, order constant (load-bearing, but documented and test-pinned) |
| `EndpointLogFields.kt` | 2 | Shape-owning enum; fail-open `addKeyValue`; mostly declarative |
| `MdcKeys.kt` | 2 | MDC scope save/restore |
| `NanoTimeSource.kt`, `CorrelationIdGenerator.kt` | 1 | Trivial functional interfaces |

Test code (weight as safety proof):

| Test class | Score | Rationale |
|---|---|---|
| `RequestLoggingFilterIntegrationTest` | 4 | Proves the real wiring (registration, MVC dispatch, tees on Tomcat streams, error dispatch) |
| `RequestLoggingFilterTracingIntegrationTest` | 4 | Pins the convention chain the trace capture rests on (order, scoping, MDC correlation) |
| `RequestLoggingFilterAsyncTest` | 4 | Safeguards the marker+destruction model and exactly-once |
| `RequestLoggingFailOpenCounterTest` | 3 | Proves fail-open stages by injecting real failures |
| `RequestLoggingMetricsTest` | 3 | Events counter semantics (incl. level-gate rule), gauge lifecycle, size distributions |
| `RequestLoggingFilterTest` | 3 | Core line/level/MDC/exclusion behavior |
| `RequestLoggingFilterBodyAndHeaderTest` | 3 | Tee truthfulness, truncation, masking, wildcard selection |
| `RequestLoggingFilterTraceContextTest` | 3 | Capture/overlay/restore of trace MDC |
| `EndpointLogFieldTest` | 3 | Index contract: literal names, template lockstep, sensitivity flags |
| `RequestLoggingAutoConfigurationTest` | 3 | Back-off/override wiring |
| `EndpointLoggingReferenceConfigTest` | 2 | Docs lockstep |
| `AwaitingAppender` | 2 | Test infrastructure |

## 5. Findings

### 🟠 High

- [x] 1. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:71-126] {High} {Confidence: high} {robustness} The pre-chain section is not covered by the documented fail-open contract
  - Symptom → cause: class KDoc and README promise "a failure inside the logging itself … never propagates into request processing", but everything from correlation resolution (line 71) to the arrival line (line ~124) runs *before* the guarded `try` at line 128: `correlationIds.nextCorrelationId()` (host-provided bean), `metrics.correlationId(...)`, capture/wrapper construction, `properties.requestHeaders.select(Collections.list(request.headerNames), …)`, the `Exchange` build including `nanoTime.nanoTime()` (host-provided bean), `MdcScope` creation. An exception from any of these propagates out of `doFilterInternal` and fails the request with a 500 — the exact outcome fail-open exists to prevent. Only `logRequestStart` is internally guarded.
  - Triggering condition: only when a host-overridden bean (`CorrelationIdGenerator`, `NanoTimeSource`) throws, or a container/spec edge in header enumeration (see finding 9) — not in normal operation with the defaults.
  - Impact: every request through the filter crashes (500) while the fault is intermittent in a *logging* component; escalates to a full outage if a host bean fails systematically. Additionally none of the fail-open counters fires, so the metric channel is blind to it too.
  - Fix strategy: confine the pre-chain wiring in its own catch that degrades to plain pass-through (`filterChain.doFilter(request, response)`) plus a `wiring` fail-open count, so the request survives an arbitrary wiring failure; alternatively narrow the documented contract to match the code.

### 🟡 Medium

- [x] 2. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/ExchangeLogEmitter.kt:85-133] {Medium} {Confidence: high} {robustness} The pre-gate section of `logExchange` sits outside the fail-open guard
  - Symptom → cause: after the exactly-once CAS (line 86), `nanoTime.nanoTime()` (line 91), the `exchange.response.status` read (line 93), `recordBodySizes` (line 96) and the level/outcome resolution run *before* the guarded `try` at line 141. An exception there (a throwing host time source; a response object misbehaving at destruction time — precisely the failure class the emission-failure test injects, only earlier) escapes into the container's `requestDestroyed` invocation, and the event is lost **without** `endpoint.logging.failopen{stage=emission}` counting it — defeating the stated purpose of that counter ("the missing line is the symptom").
  - Triggering condition: only edge case (broken injected bean, container returning a throwing response facade at destruction).
  - Impact: lost exchange event that is invisible on the metric channel built to detect exactly this; a stack trace in the container log at listener level.
  - Fix strategy: move the guard boundary up so the whole method body after the CAS is confined, counting `emissionFailure()` on any escape (1 sentence: widen the try, keep the CAS outside).

- [x] 3. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/CapturingResponseWrapper.kt:34-86] {Medium} {Confidence: high (mechanism) / medium (frequency)} {correctness} The response tee does not observe `reset()`/`resetBuffer()`
  - Symptom → cause: the tee copies every byte at write time, but when the application or framework discards the uncommitted buffer (`response.reset()` / `resetBuffer()` — e.g. an `@ExceptionHandler` rewriting a partially-written response), the capture keeps the discarded bytes. The logged `endpoint_response_body` then shows discarded + final content concatenated, and `endpoint.response.body.size` over-counts relative to what the client received.
  - Triggering condition: only edge case — error-handling paths that rewrite an uncommitted response after bytes were written.
  - Impact: wrong logged body / inflated size metric for exactly the exchanges one investigates most (failures); no functional damage to the response itself.
  - Fix strategy: override `reset()`/`resetBuffer()` in the wrapper to clear (or mark) the capture, or document the tee as "bytes written, not bytes delivered" in the field's KDoc and README.

- [x] 4. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/BoundedBodyCapture.kt:25-47] {Medium} {Confidence: medium} {concurrency} Capture visibility across threads rests on container-internal happens-before only
  - Symptom → cause: `buffer` (plain `ByteArrayOutputStream`) and `totalBytes` (plain `var`) are written by whatever thread performs body I/O (an async worker) and read at request destruction on a container thread. The class KDoc claims the container's ordering guarantee suffices, but ordering is argued, not established by this module: no field is volatile and no lock is taken, so the JMM edge exists only if the container's async state machine happens to synchronize between the last write and the destruction callback (Tomcat's does today).
  - Triggering condition: only concurrent — async exchanges with body capture/measurement, on a container (or future Tomcat version) whose completion path lacks the incidental edge; classic heisenbug profile, low probability.
  - Impact: stale or torn body content/size in the event and metric — silently wrong data, no crash.
  - Fix strategy: piggyback the handoff on the module's own synchronization (e.g. publish capture totals through the existing `@Volatile` exchange fields at completion-marking time, or make the capture's fields volatile) so correctness stops depending on unspecified container internals.

- [x] 5. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:74] {Medium} {Confidence: low-medium — needs verification} {robustness} The correlation echo header is plausibly lost on container error dispatch
  - Symptom → cause: the correlation id is echoed via `response.setHeader` at filter entry. When an unhandled exception reaches the container, Tomcat's error dispatch resets the (uncommitted) response before rendering the error page — depending on version and path this clears previously set headers, so the client would receive the 500 *without* the correlation id, on exactly the requests where the id matters most for support. Spring-handled errors (`@ExceptionHandler`) that call `reset()` themselves have the same effect deterministically.
  - Triggering condition: only edge case — unhandled exceptions / error dispatch with an uncommitted response.
  - Impact: broken client-side correlation on failure responses; the promise "the id is always echoed" (reference YAML, README) would be silently false for the failure class.
  - Fix strategy: verify against a real container first (extend the existing boom integration test with a header assertion, see finding 6); if confirmed, re-set the header at a point that survives error dispatch or document the limitation.

- [x] 6. [./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterIntegrationTest.kt:—, boom test] {Medium} {Confidence: high} {test quality} The error path's client-visible behavior is unasserted
  - Symptom → cause: the 500 integration test asserts the *event* thoroughly (level, outcome, cause chain, final status) but nothing about the *response the client received* beyond the status code — neither the correlation echo header (finding 5's open question) nor selected response headers. The one place where an operator-facing promise ("always echoed") lacks a proof, at a score-5 spot.
  - Triggering condition: always (it is a coverage gap, not a runtime defect).
  - Impact: finding 5 stays unverifiable from the build; a regression in error-path header behavior would ship green.
  - Fix strategy: add header assertions on the boom test's HTTP response (and, if finding 5 confirms, adjust code or docs accordingly).

### 🟢 Low

- [x] 7. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingProperties.kt:129] {Low} {Confidence: high} {correctness} Multi-value headers are logged first-value-only — `select` resolves via `getHeader`, so repeated headers (`Set-Cookie`, multiple `Accept`) silently lose all but the first value; not documented. Fix strategy: document, or resolve via `getHeaders`/`getHeaderNames` joining values.
- [x] 8. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/EndpointLoggingMetrics.kt:56-64] {Low} {Confidence: high} {resources} A second filter instance against the same registry registers a gauge whose backing `AtomicLong` is silently discarded (Micrometer dedupes by id and keeps the first) — that instance's open-exchange movements become invisible. Single-filter production is unaffected; the metrics test already constructs this situation harmlessly. Fix strategy: document, or make the gauge backing shared per registry.
- [x] 9. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:105] {Low} {Confidence: medium} {robustness} `Collections.list(request.headerNames)` — the servlet spec permits `getHeaderNames()` to return `null` (container withholding header access), which would NPE in the pre-chain section (amplifies finding 1). No modern container does this. Fix strategy: null-tolerant read.
- [x] 10. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:138-139] {Low} {Confidence: medium} {correctness} An async cycle that starts *and completes* within the chain leaves `isAsyncStarted` false in the `finally` — the exchange logs `endpoint_async=false` and registers no marker, so a timeout/error in that window would go unmarked. Narrow timing edge. Fix strategy: additionally consult `request.getDispatcherType`/`isAsyncSupported` heuristics or document the boundary.
- [x] 11. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/ExchangeLogEmitter.kt:93] {Low} {Confidence: high} {robustness} For `outcome=timeout` (and async `onError`), `endpoint_response_status_code` reports whatever the response object holds — typically a stale 200 that the client never saw as a success. The status/outcome contradiction is undocumented. Fix strategy: document, mirroring the already-documented pre-error-dispatch discussion.
- [x] 12. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/ExchangeLogEmitter.kt:60-77,190-205] {Low} {Confidence: high} {test quality} The `InterruptedException` fail-open branches (arrival and emission) are untested — interrupt-flag restoration and the counter increment on that path have no proof. Fix strategy: inject an interrupting failure (e.g. a header selection that throws a wrapped interrupt) or accept and note the gap.
- [x] 13. [./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterBodyAndHeaderTest.kt:60-67] {Low} {Confidence: high} {test quality} The `handle` helper lacks the `try/finally` around `doFilterInternal` that its siblings in the core and metrics tests have — a throwing chain in a future test would skip destruction and produce a confusing secondary failure. Currently only happy-path chains use it. Fix strategy: align the helper with the core test's shape.
- [x] 14. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingProperties.kt:116-133] {Low} {Confidence: high} {maintainability} `"*"` in `excludes` is a silent no-op (the literal never matches a header name), unlike its documented meaning in `includes` and `masked` — a plausible misconfiguration with no feedback. Fix strategy: document, or reject the wildcard there in the init validation.

No TODO/FIXME debt exists in the module (verified by grep) — noted per the maintainability heuristic.

## 6. Systemic patterns

1. **"The guard starts after fallible work" (2 sites; counted by reading the three guarded regions).** The fail-open discipline is thorough *inside* its `try` blocks, but twice the block opens only after non-trivial, fallible work has already run: the filter's pre-chain wiring (finding 1) and the emitter's pre-gate section (finding 2). Both escapes bypass the very counters built to observe fail-open events. Representative locations: `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:71-126`, `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/ExchangeLogEmitter.kt:85-133`. Remediation is one boundary move per site.
2. **First-value-only header reads (2 sites; via grep for `valueOf(name)` / `getHeader` in the selection path).** Both the request-side capture (`RequestLoggingFilter.kt:105` feeding `HeaderLogProperties.select`) and the response-side emission (`ExchangeLogEmitter.kt` response-header selection) resolve header values through single-value lookups, so multi-value headers are silently truncated everywhere headers are logged (finding 7 is the representative).


---

## 7. Remediation status (2026-08-21)

All 14 findings were addressed in commit `e6094cc5` (follow-up session to this analysis; the analysis
itself, per its scope boundary, changed no code). Resolution per finding:

| Finding | Resolution |
|---|---|
| 1 (High, wiring not fail-open) | **Code fix** — pre-chain wiring extracted to `wireExchange()`, confined in `doFilterInternal`; failure degrades to pass-through, counted `stage=wiring`; proven by test |
| 2 (emitter pre-gate outside guard) | **Code fix** — guard widened to everything after the exactly-once CAS; pre-gate failures counted `stage=emission`; proven by test |
| 3 (tee ignores reset) | **Code fix** — `reset()`/`resetBuffer()` overridden, capture cleared with the container buffer (`super` first); proven by test |
| 4 (capture visibility) | **Code fix** — `totalBytes` volatile, written last per mutation; readers read it first; the happens-before edge is now module-owned |
| 5 (echo lost on error dispatch) | **Verified OK** — asserted against real Tomcat: the echo header survives the error dispatch (buffer reset, not header reset); no code change needed |
| 6 (error path unasserted) | **Test added** — boom path now asserts the client-visible echo on the 500 |
| 7 (first-value-only headers) | **Code fix** — multi-value, comma-joined resolution on both sides; proven by test |
| 8 (gauge dedupe on second instance) | **Documented** — one-instance-per-registry limitation in `EndpointLoggingMetrics` KDoc |
| 9 (nullable header enumeration) | **Code fix** — null-tolerant enumeration in `wireExchange()` |
| 10 (async-within-chain mislabel) | **Documented** — boundary noted at the `isAsyncStarted` check |
| 11 (stale status under timeout) | **Documented** — semantics noted on `EndpointLogField.RESPONSE_STATUS_CODE` |
| 12 (interrupt branches untested) | **Test added** — emission interrupt branch incl. flag restoration |
| 13 (helper without finally) | **Test fix** — helper aligned with its siblings |
| 14 (wildcard exclude no-op) | **Code fix** — rejected at binding time; proven by test (`HeaderLogPropertiesTest`) |

Suite after remediation: 82 tests, 0 failures (`mvn verify` including ktlint).
