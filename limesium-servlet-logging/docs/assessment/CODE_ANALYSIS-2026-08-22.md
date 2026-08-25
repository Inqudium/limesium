# Code & Defect Analysis: limesium-servlet-logging

> **Status: ✅ REMEDIATED (2026-08-22).** All 13 findings are resolved — fixed, tested, or documented as
> an explicit contract boundary — in the commit pair `143b2753` (body-wrapper semantics, findings
> 1/4/6/7/11/12) and `8ed0f8fd` (async MDC, callback-true outcomes, fail-open guards, findings
> 2/3/5/8/9/10/13); the per-finding resolution is tabled in
> [section 7](#7-remediation-status-2026-08-22). Everything below this banner describes the state **at
> the analyzed commit** `89f6bff70f8150074a82c9a15fb15bcfe3ec69bf` and is retained unchanged as the
> audit record.

1. Identification of the codebase
   - **Repository:** `https://github.com/dhaase/tool-box.git`
   - **Commit hash:** `89f6bff70f8150074a82c9a15fb15bcfe3ec69bf` (full)
   - **Reference (branch/tag):** `refs/heads/main`; the unrelated `./limesium-reactive-logging/docs/assessment/CODE_ANALYSIS.md` was already modified in the working tree at analysis start and was not part of this assessment
2. Scope of the analysis
   - **Included:** `./limesium-servlet-logging/pom.xml`, `./limesium-servlet-logging/src/main/kotlin/`, `./limesium-servlet-logging/src/main/resources/`, `./limesium-servlet-logging/src/test/kotlin/`, and `./limesium-servlet-logging/src/test/resources/` (relative to the workdir). **Test code is part of the analysis** and is treated both as a safety proof and as an analysis subject in its own right.
   - **Excluded:** build output and generated reports (`./limesium-servlet-logging/target/`), module documentation (consulted only as contract context), the pre-existing assessment being replaced, sibling modules, and pure security analysis. The repository-level `./pom.xml` and `./CLAUDE.md` were consulted for versions and conventions but are not analysis subjects.
3. Analysis environment & tools
   - **Target environment:** OpenJDK 21, Kotlin 2.4.10, Spring Boot 4.1.0 / Spring Framework 7.0.8, Jakarta Servlet 6.1, embedded Tomcat 11.0.22; blocking Spring MVC/Servlet stack. No virtual threads, persistence, messaging, Reactor, or Kotlin coroutines are used by this module.
   - **Build system:** Maven 3.9.15, multi-module reactor; analysis host Oracle JDK 26.0.1 (the project compiles for Java 21)
   - **Analysis tools used:** complete manual source and test review, `rg` pattern sweeps, Git history/status inspection, JDK/Maven version inspection, and the locally installed primary source artifacts for Spring Framework, Spring Boot, Jakarta Servlet, Tomcat, and JDK `PrintWriter`. The parent configures ktlint 3.0.0 and JaCoCo 0.8.12, but no defect-oriented static analyzer (detekt, SpotBugs, Error Prone, NullAway, or Sonar) is configured. No build or test was executed because the supplied analysis prompt requires separate approval before local verification.
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/tool-box` (absolute reference point; all relative paths refer to it)
   - **Report output path:** `./limesium-servlet-logging/docs/assessment/CODE_ANALYSIS.md` (relative to the workdir)
   - **Scope root (relative to the workdir):** `./limesium-servlet-logging/`
   - **Path convention for finding locations:** `<path relative to the workdir>:<line>` (for example, `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:124`)

---

## 1. Executive summary

The module has a well-factored observability core: correlation and time are injectable, completion is emitted exactly once at request destruction, mutable async state uses volatile/atomic hand-offs, body capture is bounded, and the real-Tomcat suite gives the main Spring MVC path substantial protection. No Critical or High finding was identified. The most consequential current defect is in request character handling: when the request has no declared encoding, the capture wrapper creates the application's `Reader` with UTF-8 instead of preserving the Servlet/Tomcat reader semantics, so enabling request body logging or even size measurement can change non-ASCII input seen by the application. Async support also has two contract gaps: endpoint MDC exists only on the initial dispatch thread, and body wrappers survive only when downstream code uses the two-argument `startAsync(request, response)` path that Spring MVC happens to use. Container error dispatches and `sendError`/redirect buffer replacement do not traverse or reset the response capture, producing missing or stale error bodies and size metrics. The response writer is not byte-faithful across split character sequences or I/O failures, and async outcome classification depends incorrectly on whether a callback supplies a throwable. The remaining fail-open gap is uncommon but real: MDC setup/cleanup and the arrival logger's level gate sit outside the guards that promise logging can never affect a request.

**Test verdict.** The suite is deterministic, isolated, and sensibly pyramidal: fast servlet-mock tests carry most behavior, with focused real-Tomcat and real-tracing integration tests at the framework boundaries. It is a useful safety net for the normal Spring MVC path, but it creates false confidence at several score-5 wrapper/async boundaries because fixtures pin UTF-8, use single ASCII writes, and observe only the final exchange event rather than async-handler context or generic Servlet async topology.

Most significant gaps and anomalies:

- no test exercises an absent request encoding with non-ASCII `getReader()` input or the request stream/reader exclusivity contract;
- no test observes endpoint MDC inside a `Callable`/`DeferredResult` worker, or retains body wrappers through a direct zero-argument Servlet `startAsync()`;
- the error-dispatch tests enable response-body logging but never compare the client-visible error body or body-size metric with the captured values, and no test covers `sendError` after buffered output;
- writer tests use one ASCII write and never exercise a split surrogate/stateful encoding or a failing delegate `PrintWriter`;
- async tests omit `onTimeout` with a throwable and `onError` without one, the two complements that expose the outcome-state defect.

## 2. Scope & methodology

- **Analyzed:** all 13 production Kotlin files, the production auto-configuration resource, the module POM, all 14 Kotlin test/helper files, and the test logging resource. Test code was fully in scope.
- **Stack detected:** Spring Boot library module for blocking Servlet/Spring MVC applications, registered as a `OncePerRequestFilter` plus `ServletRequestListener`; embedded Tomcat is test/runtime host. Async state crosses initial dispatch, MVC worker/redispatch, `AsyncListener`, and request-destruction threads. There is no database, transaction manager, broker, scheduler, Reactor chain, coroutine runtime, or virtual-thread configuration in this module.
- **Review order:** files were ranked before deep review and then read from score 5 downward. The review traced request/response state from filter activation through correlation wiring, MDC scope, request/response wrappers, synchronous and asynchronous dispatch, timeout/error callbacks, container error rendering, request destruction, metrics, and final log emission.
- **External semantic checks:** exact-version primary sources were inspected locally. Jakarta Servlet 6.1 specifies that zero-argument `startAsync()` retains the original unwrapped request/response and that `sendError`/redirects clear the response buffer; Spring's `OncePerRequestFilter` skips error and async dispatches by default; Spring MVC's `StandardServletAsyncWebRequest` instead calls the two-argument `startAsync(request, response)`; Tomcat 11.0.22 defaults an otherwise unspecified request body charset to ISO-8859-1; JDK `PrintWriter` suppresses I/O exceptions and owns its error flag.
- **Static analysis:** ktlint is style-only and JaCoCo measures coverage; no configured defect-pattern or static-nullness analyzer supplements the manual pass.
- **Local verification:** none in this assessment. In accordance with the supplied prompt, reproducing tests or Maven execution await explicit user approval. Existing `./limesium-servlet-logging/target/` artifacts, if any, were excluded and are not verification evidence.
- **Known limitations / blind spots:** no runtime reproducer was executed for alternate encodings, raw Servlet async, client disconnects, or error-page capture; no load/concurrency profile was performed. Framework behavior was killed against exact local primary sources, but container-specific behavior beyond the target Tomcat version was not surveyed.

## 3. Statistics

| Severity | Count |
|---|---:|
| 🔴 Critical | 0 |
| 🟠 High | 0 |
| 🟡 Medium | 11 |
| 🟢 Low | 2 |
| **Total** | **13** |
| Systemic patterns | 3 |

## 4. File ranking (Phase 1)

Production and configuration files:

| File | Score | Rationale |
|---|---:|---|
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt` | 5 | Hot-path filter choreography, fail-open boundaries, MDC lifecycle, wrapper hand-off, async listener registration, destruction-time emission |
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/CapturingRequestWrapper.kt` | 5 | Servlet stream/reader semantics, charset behavior, non-blocking I/O and async wrapper retention |
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/CapturingResponseWrapper.kt` | 5 | Output stream/writer semantics, byte accuracy, reset/error behavior, non-blocking and async response I/O |
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/Exchange.kt` | 5 | Mutable state crossing dispatch/listener/destruction threads and async disposition marking |
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/ExchangeLogEmitter.kt` | 5 | Final outcome/level/status decisions, body/header rendering, MDC overlay and fail-open emission |
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/BoundedBodyCapture.kt` | 4 | Mutable byte accumulator, cross-thread visibility, size accounting, truncation and reset semantics |
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/MdcKeys.kt` | 4 | Thread-local overlay/restoration on pooled request and destruction threads |
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingAutoConfiguration.kt` | 4 | Filter/listener registration, async/dispatcher behavior and trace-filter ordering |
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/EndpointLoggingMetrics.kt` | 3 | Registry identity, gauge lifecycle, counters and dynamically tagged body summaries |
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingProperties.kt` | 3 | Binding invariants, path controls, header selection/masking and capture switches |
| `./limesium-servlet-logging/pom.xml` | 3 | Runtime/test topology, provided Servlet API and real Tomcat/tracing integration dependencies |
| `./limesium-servlet-logging/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 2 | Static activation of the single auto-configuration |
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/EndpointLogFields.kt` | 2 | Type-checked structured-field contract with confined rejection |
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/CorrelationIdGenerator.kt` | 1 | Trivial injectable UUID source |
| `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/NanoTimeSource.kt` | 1 | Trivial injectable monotonic time source |

Test files and resources (score reflects their weight as safety proof):

| File | Score | Rationale |
|---|---:|---|
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterIntegrationTest.kt` | 5 | Real Tomcat/MVC, stream wrappers, async dispatch, error dispatch and correlation echo |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterAsyncTest.kt` | 5 | Async timing, listener outcomes, exactly-once emission and cross-thread state hand-off |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterBodyAndHeaderTest.kt` | 5 | Score-5 request/response wrappers, reset behavior, header selection/masking and truncation |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterTest.kt` | 5 | Core filter behavior, exception identity, MDC scope, level/outcome and path activation |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFailOpenCounterTest.kt` | 5 | Injected wiring/emission failures and fail-open metric contract |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingMetricsTest.kt` | 4 | Gauge/counter lifecycle and body-size accuracy independent of logger gates |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterTraceContextTest.kt` | 4 | Destruction-thread trace overlay and exact MDC restoration |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterTracingIntegrationTest.kt` | 4 | Real Tomcat plus Brave bridge and async trace join |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingAutoConfigurationTest.kt` | 4 | Boot conditional wiring, property binding and host bean overrides |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/EndpointLogFieldTest.kt` | 3 | Wire/type/index mapping contract and field-level fail-open behavior |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/EndpointLoggingReferenceConfigTest.kt` | 3 | Property/default documentation lockstep proof |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/AwaitingAppender.kt` | 3 | Bounded cross-thread integration-event capture and deferred MDC snapshot |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/HeaderLogPropertiesTest.kt` | 2 | Focused validation and wildcard behavior |
| `./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/TwinContractTest.kt` | 2 | Literal cross-stack meter/MDC/masking pins |
| `./limesium-servlet-logging/src/test/resources/logback-test.xml` | 1 | Static test logging configuration |

## 5. Findings

### 🔴 Critical

Nothing to report in this severity class.

### 🟠 High

Nothing to report in this severity class.

### 🟡 Medium

- [x] 1. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/CapturingRequestWrapper.kt:67] {Medium} {Confidence: high} {correctness} The capture wrapper can change the request text delivered to the application
  - Symptom → cause: `getReader()` is rebuilt over the tee input stream using `bodyCharset`, whose null/invalid fallback is UTF-8. Tomcat 11.0.22 uses ISO-8859-1 for an otherwise unspecified request body charset, and the KDoc's claim that the fallback affects only the log line is therefore false: the replacement reader also decodes the application's input. Boot's default character-encoding filter normally forces UTF-8 before this filter, which lowers frequency but does not protect hosts that disable, replace, or reorder that auto-configuration.
  - Triggering condition: request body logging or request-size measurement is enabled; application code calls `getReader()`; the request/container supplies no usable character encoding; and the payload contains bytes whose UTF-8 and container-default decoding differ.
  - Impact: the logging/measurement feature can hand the application changed characters and therefore cause wrong validation, persistence, routing, or responses rather than merely producing an inaccurate log.
  - Fix strategy: preserve the container's reader charset semantics for application delivery and keep any log-display fallback separate from that reader. Add an exact target-container proof for an unspecified encoding before treating UTF-8 as safe.

- [x] 2. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:124] {Medium} {Confidence: high} {concurrency} Endpoint MDC is lost during asynchronous handler work
  - Symptom → cause: the `MdcScope` covers only the initial filter-chain invocation and closes as soon as that dispatch returns. The filter retains `OncePerRequestFilter`'s default of skipping async dispatches, and it installs no Spring MVC callable/deferred interceptor or executor context propagator, so a `Callable`, `DeferredResult`, or equivalent worker executes without `endpoint_request_id`, `endpoint_method`, and `endpoint_route`; the destruction-time emitter restores the keys only for the final exchange event.
  - Triggering condition: every asynchronous request whose application code logs on a worker thread after the initial dispatch scope has closed.
  - Impact: application logs from the actual async work cannot be correlated with the exchange even though the module advertises request identity in MDC while the request is handled.
  - Fix strategy: propagate an additive snapshot of the module-owned MDC keys into supported async executors/callbacks and restore it afterwards, or run a lightweight identity-only scope on async dispatch without wiring a second exchange. Prove handler-side logs, not only the final emitter overlay.

- [x] 3. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:121] {Medium} {Confidence: high (framework contract)} {correctness} Generic zero-argument Servlet async processing discards the body-capture wrappers
  - Symptom → cause: the wrappers are passed only to the current filter chain. Jakarta Servlet specifies that zero-argument `startAsync()` initializes the `AsyncContext` with the original unwrapped request and response; `ServletRequestWrapper.startAsync()` simply delegates that call. Spring MVC's `StandardServletAsyncWebRequest` happens to preserve the wrappers by calling the two-argument overload, which is why the existing integration succeeds, but a servlet or third-party component using the standard zero-argument API reads/writes through the originals during async work and redispatch.
  - Triggering condition: request or response body logging/measurement is enabled and downstream Servlet code starts async processing with `startAsync()` rather than `startAsync(currentRequest, currentResponse)`.
  - Impact: request/response body fields are missing or partial and size metrics undercount async bytes; the documented async-safe tee contract becomes framework-adapter-dependent.
  - Fix strategy: make wrapper retention an owned invariant for both directions across the zero-argument async API, including configurations that capture only the response. Otherwise explicitly narrow the supported async contract to frameworks that lock in the supplied wrappers.

- [x] 4. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/CapturingResponseWrapper.kt:69] {Medium} {Confidence: high (framework contract)} {correctness} Error/redirect buffer replacement bypasses capture reset and final error rendering bypasses the tee
  - Symptom → cause: the wrapper clears capture only when callers invoke its `reset()` or `resetBuffer()`. Servlet `sendError` and the clearing redirect variants reset/replace the delegate's response buffer without calling those wrapper overrides; separately, `OncePerRequestFilter` skips the container's ERROR dispatch by default, so the rendered error body is written through the original response. An explicit `sendError` after buffered output can therefore log discarded pre-error bytes, while an unhandled error commonly logs no response body at all.
  - Triggering condition: response body logging or measurement is enabled and the response is replaced through `sendError`/a clearing redirect, or an outer container error dispatch renders the final body.
  - Impact: deterministic stale or missing `endpoint_response_body` values and incorrect response-size samples on precisely the failure/redirect paths operators investigate.
  - Fix strategy: align capture state with every Servlet operation that clears/replaces the buffer and ensure container error rendering traverses an equivalent response capture. If portable wrapping across that boundary is not feasible, narrow and meter the documented omission.

- [x] 5. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/Exchange.kt:73] {Medium} {Confidence: high} {correctness} Async outcome is inferred from throwable presence instead of the callback that occurred
  - Symptom → cause: `onTimeout` marks `timedOut` and also stores an optional throwable, while the emitter checks `failure != null` before `timedOut`; a timeout carrying a throwable is consequently logged as ERROR/`failure`, not WARN/`timeout`. Conversely, `onError` sets no explicit error flag and does nothing when its `AsyncEvent` has no throwable, so a still-200 response can be logged as INFO/`success`; Servlet's `AsyncEvent` explicitly permits a null throwable.
  - Triggering condition: an async timeout callback includes a throwable, or an error callback arrives without one and no later status update reaches 5xx.
  - Impact: wrong outcome/level, wrong emitted-events counter tag, and misleading timeout/failure dashboards.
  - Fix strategy: track timeout and async-error disposition independently from the optional cause, give timeout a defined precedence, and attach a supplied throwable without letting it redefine which callback occurred.

- [x] 6. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/CapturingResponseWrapper.kt:87] {Medium} {Confidence: high} {correctness} Writer capture encodes each write independently and is not byte-equivalent to the container writer
  - Symptom → cause: every character chunk is converted with a fresh `String(...).toByteArray(charset)`. A real response writer uses a stateful encoder/buffer; a surrogate pair split across two `write` calls, or another stateful charset sequence crossing calls, can be emitted correctly to the client but converted to replacement bytes in each capture chunk.
  - Triggering condition: response-body logging or measurement is enabled, the character API is used, and a multi-unit character or stateful encoding sequence straddles writer calls.
  - Impact: logged text differs from the client response and the response-body-size summary records the wrong byte count.
  - Fix strategy: capture the actual encoded byte stream below the writer or maintain one encoder with the same lifecycle and replacement policy as the delegate. Verify split sequences across write, flush, close, and reset boundaries.

- [x] 7. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/CapturingResponseWrapper.kt:92] {Medium} {Confidence: high} {robustness} The wrapper hides delegate `PrintWriter` failures and captures characters that may not have flowed
  - Symptom → cause: the delegate returned by `super.getWriter()` is itself a `PrintWriter`, which suppresses `IOException` and records an internal error flag. The anonymous tee `Writer` cannot see that flag, captures the full chunk unconditionally, and is then wrapped in a second `PrintWriter`; the outer writer's `checkError()` inspects its anonymous `Writer`, not the inner `PrintWriter`, so it can return false after the real writer failed.
  - Triggering condition: the container writer encounters an I/O failure, commonly a client disconnect or failed flush/close, while response capture is active and the character API is used.
  - Impact: body logs and size metrics claim bytes flowed when they did not, and application code using `checkError()` loses the response failure signal it would have received from the original writer.
  - Fix strategy: preserve the delegate writer's error-state contract and only count output at a layer where successful byte delivery is observable. Add failing-write, flush, close, and `checkError()` proofs.

- [x] 8. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/ExchangeLogEmitter.kt:43] {Medium} {Confidence: high (gap) / low (trigger)} {robustness} Arrival/MDC work still sits outside the advertised fail-open boundary
  - Symptom → cause: the arrival logger's `isInfoEnabled` call executes before its `try`, while the filter constructs `MdcScope` before entering the chain `try` and calls `close()` from an unguarded inner `finally`. A failing logging backend or MDC adapter can therefore abort before the chain runs, leak partially installed MDC, or let cleanup mask the application's original exception; the arrival failure counter sees none of those paths.
  - Triggering condition: a custom or malfunctioning SLF4J/MDC implementation fails during the arrival level gate, MDC lookup/put, or MDC restoration; the level-gate path additionally requires `log-request-start=true`.
  - Impact: an optional observability feature can fail an HTTP request and/or pollute a pooled thread, contradicting the whole-filter fail-open contract.
  - Fix strategy: make one outer fail-open lifecycle cover level lookup, scope construction, arrival emission, downstream invocation boundaries, and scope restoration without misclassifying an application exception. Count a confined arrival loss while ensuring cleanup failure cannot replace the chain result.

- [x] 9. [./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterAsyncTest.kt:59] {Medium} {Confidence: high} {test quality} The async safety proofs observe only completion state and omit the topology/disposition boundaries in findings 2, 3, and 5
  - Symptom → cause: the mock suite enables no body capture, executes no application log on an async worker, and manually supplies only timeout-without-throwable and error-with-throwable events. The real async integration uses Spring MVC's wrapper-preserving two-argument `startAsync` adapter and again inspects only the final emitter's MDC. All tests remain green when generic Servlet async drops wrappers, handler MDC is absent, or the two complementary callback shapes are misclassified.
  - Triggering condition: every CI run; mutations matching findings 2, 3, and 5 do not alter asserted values.
  - Impact: false confidence in the module's broad `Callable`/`DeferredResult`/Servlet async claims at its highest-risk lifecycle boundary.
  - Fix strategy: add a raw Servlet/Tomcat async endpoint that uses zero-argument `startAsync`, reads/writes through its `AsyncContext`, and logs from the worker; assert captured bodies, sizes, and handler MDC. Complete the callback matrix with nullable and non-null throwable variants.

- [x] 10. [./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterIntegrationTest.kt:213] {Medium} {Confidence: high} {test quality} The real error-dispatch tests do not assert the enabled response capture
  - Symptom → cause: the integration context globally enables `log-response-body=true`, and `/it/boom` deliberately goes through Tomcat's error dispatch, yet the tests assert only response status, correlation echo, outcome, and cause. They never compare the client-visible rendered error body with `endpoint_response_body`, never enable/inspect the size summary, and never write then call `sendError`.
  - Triggering condition: every CI run; finding 4 leaves all current assertions unchanged.
  - Impact: the only real-container error proof actively exercises but does not validate the wrapper boundary, so stale/missing failure payloads appear covered when they are not.
  - Fix strategy: configure deterministic error rendering, assert exact client/capture agreement and response-size metrics, and add a partial-write-plus-`sendError`/redirect case.

- [x] 11. [./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterBodyAndHeaderTest.kt:90] {Medium} {Confidence: high} {test quality} Body wrapper tests pin away the character and writer failure modes in findings 1, 6, and 7
  - Symptom → cause: the request-reader test explicitly sets UTF-8 and uses ASCII, while the response-writer test performs one ASCII write against a healthy mock writer. The fixtures make container-default decoding and wrapper-default UTF-8 indistinguishable, never split an encoded character across writes, and cannot expose the inner/outer `PrintWriter` error-state mismatch.
  - Triggering condition: every CI run; the three production defects preserve the asserted ASCII strings.
  - Impact: false confidence that the wrappers are transparent to application input and byte-faithful to client output.
  - Fix strategy: add an unspecified-encoding non-ASCII request against the target container, split surrogate/stateful response writes, and a deterministic failing delegate whose `checkError()` behavior is asserted before and after wrapping.

### 🟢 Low

- [x] 12. [./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/CapturingRequestWrapper.kt:33] {Low} {Confidence: high} {correctness} The request wrapper no longer enforces the Servlet input-stream/reader either-or contract. `getReader()` obtains and caches the tee stream, after which either accessor returns a cached object without asking the delegate to reject the second API; erroneous downstream code can therefore consume both where an unwrapped request would throw `IllegalStateException`. Fix strategy: track which public body API was selected and reproduce the delegate's rejection semantics while still implementing the reader over the tee.

- [x] 13. [./limesium-servlet-logging/src/test/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilterTest.kt:483] {Low} {Confidence: high} {test quality} The invalid-path-pattern test asserts only that some throwable exists, so any unrelated constructor regression satisfies it. Fix strategy: assert the parser/configuration exception type and a message fragment identifying the malformed pattern.

## 6. Systemic patterns

1. **The body wrappers reproduce Servlet APIs but miss six semantic boundaries.** Counting basis: complete manual review of both wrapper classes against Servlet/Tomcat/JDK contracts. The representative boundaries are request reader charset, input-stream/reader exclusivity, zero-argument async wrapper retention, error/redirect buffer replacement, stateful writer encoding, and `PrintWriter` error state (`./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/CapturingRequestWrapper.kt`, `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/CapturingResponseWrapper.kt`). Findings 1, 3, 4, 6, 7, and 12 are the concrete effects.
2. **Async tests validate the final event but not the execution context or alternate Servlet topology (approximately five missing boundary checks across two primary suites).** Counting basis: mutation review of `RequestLoggingFilterAsyncTest` and the async/error methods in `RequestLoggingFilterIntegrationTest`: worker MDC, raw zero-argument async wrapper retention, async body-size capture, timeout-with-throwable, and error-without-throwable are absent. Findings 2, 3, 5, and 9 describe the resulting blind spots.
3. **Fail-open guards do not cover the complete logging-owned operation (three operations across two production files).** Counting basis: inspection of every pre-chain MDC/arrival boundary. Arrival level lookup, chain-scope construction, and scope restoration sit outside the corresponding guards (`./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/ExchangeLogEmitter.kt:43`, `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:124`, and `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:173`). Finding 8 captures their shared impact.

## 7. Remediation status (2026-08-22)

All 13 findings were re-verified against the code and confirmed before remediation; the Servlet 6.1 API surface (both `sendError` variants and all four `sendRedirect` variants delegated individually by `HttpServletResponseWrapper`) was additionally confirmed against the shipped `jakarta.servlet-api-6.1.0` bytecode. The fixes landed in two commits — `143b2753` (body-wrapper semantics), `8ed0f8fd` (async MDC, callback-true outcomes, fail-open guards) — with the full module suite green (`mvn -pl limesium-servlet-logging verify`, 100 tests, ktlint clean) and the reactive twin re-verified after the shared `MdcScope` parity change (74 tests green).

| Finding | Resolution |
|---|---|
| 1 — capture wrapper can change request text | **Fixed.** The reader delivered to the application preserves the servlet contract exactly: the declared encoding, the spec default ISO-8859-1 when none is declared, and `UnsupportedEncodingException` for an unsupported one. The UTF-8 fallback now applies only to the rendered log line, as the KDoc always claimed. Pinned by an undeclared-encoding non-ASCII reader test. |
| 2 — endpoint MDC lost during async handler work | **Fixed for MVC async.** The filter registers a per-request `EndpointMdcCallableInterceptor` via `WebAsyncUtils` (spring-web only, no MVC dependency): `preProcess`/`postProcess` run on the `Callable`/`WebAsyncTask` worker thread and open/close an additive `MdcScope` there, fail-open. Boundary: `DeferredResult` producers and raw async workers run on application-owned threads — documented on the filter. Proven end-to-end by the Callable IT endpoint echoing its worker-side MDC. |
| 3 — zero-argument `startAsync()` discards the wrappers | **Documented and pinned as a contract boundary.** The Servlet spec itself mandates the original request/response for the zero-argument variant; silently overriding that would break spec-sanctioned unwrapping. The supported async capture contract (wrapper-preserving two-argument path, which Spring MVC uses) is documented on the filter and both wrappers, and a raw-servlet Tomcat test pins that zero-argument async bytes are logged as absent. |
| 4 — `sendError`/redirect bypass capture reset; error rendering bypasses the tee | **Fixed (wrapper half) / documented (dispatch half).** `sendError` and every buffer-clearing `sendRedirect` variant now discard the capture with the delegate's buffer. The container's ERROR-dispatch rendering through the original response remains a documented capture boundary (crossing it would require owning the ERROR dispatch), pinned by the real-Tomcat `sendError`-after-write and unhandled-error tests. |
| 5 — async outcome inferred from throwable presence | **Fixed.** `onError` sets its own `asyncErrored` flag; classification keys on which CALLBACK occurred, with timeout precedence — a timeout with a throwable stays WARN/`timeout` (cause attached), an error without one is ERROR/`failure`. Pinned by the two complementary callback tests. |
| 6 — writer capture not byte-equivalent across split sequences | **Fixed.** The capture runs through one stateful `OutputStreamWriter` encoder with the writer's lifecycle (flushed per write; a pending surrogate half survives the chunk boundary; close finalizes). Pinned by a split-surrogate test asserting capture/client byte identity. |
| 7 — delegate `PrintWriter` failures hidden | **Fixed.** `checkError()` on the handed-out writer also consults the delegate `PrintWriter`'s suppressed error flag. Residual documented: a chunk the delegate silently swallowed still counts as flowed — `PrintWriter` suppresses the failure below any tee; `checkError()` is the signal the servlet API offers and it is preserved. Pinned by a failing-delegate test. |
| 8 — arrival/MDC work outside the fail-open boundary | **Fixed.** The arrival level gate moved inside the emitter's guard; chain `MdcScope` construction and restoration are individually guarded in the filter (`stage=wiring`, never masking an application exception); `MdcScope` rolls back a partial install before rethrowing — the same rollback applied to the reactive twin's `MdcScope` for parity. Pinned by a throwing-TurboFilter arrival test. |
| 9 — async proofs omit topology/disposition boundaries | **Fixed.** Timeout-with-throwable and error-without-throwable complete the callback matrix; the Callable IT endpoint proves worker MDC; a raw zero-argument async servlet against real Tomcat pins the wrapper-retention boundary. |
| 10 — error-dispatch tests do not assert the enabled capture | **Fixed.** The unhandled-error test asserts the client body exists while the event carries no `endpoint_response_body` (the documented boundary), and a new partial-write-then-`sendError` IT proves the stale-body discard against real Tomcat. |
| 11 — wrapper tests pin away the charset/writer failure modes | **Fixed.** The new undeclared-encoding, split-surrogate, and failing-delegate tests are exactly the fixtures the finding demanded; UTF-8/ASCII-only fixtures no longer stand alone. |
| 12 — stream/reader either-or contract not enforced | **Fixed.** The wrapper tracks which public body API was selected and reproduces the delegate's `IllegalStateException` rejection while still serving both APIs from the one tee stream; repeated same-API access stays legal. Pinned in both directions. |
| 13 — invalid-pattern test accepts any exception | **Fixed.** The test asserts `PatternParseException` and that its detailed diagnostic names the malformed pattern. |
