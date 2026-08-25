# Code & Defect Analysis: limesium-reactive-logging

> **Status: ✅ REMEDIATED (2026-08-22).** All 12 findings are resolved — fixed, tested, or documented as
> an explicit contract boundary — in the commit series `1fe44e81` (MDC propagation contract, findings
> 1/2/8/9/11), `c8e05c7e` (lifecycle fail-open gaps, findings 4/5/7/12), and `1e14768b` (bounded tee and
> the outer-error capture boundary, findings 3/6/10); the per-finding resolution is tabled in
> [section 7](#7-remediation-status-2026-08-22). Everything below this banner describes the state **at
> the analyzed commit** `89f6bff70f8150074a82c9a15fb15bcfe3ec69bf` and is retained unchanged as the
> audit record.

1. Identification of the codebase
   - **Repository:** `https://github.com/dhaase/tool-box.git`
   - **Commit hash:** `89f6bff70f8150074a82c9a15fb15bcfe3ec69bf` (full)
   - **Reference (branch/tag):** `refs/heads/main`; clean working tree at analysis start
2. Scope of the analysis
   - **Included:** `./limesium-reactive-logging/pom.xml`, `./limesium-reactive-logging/src/main/kotlin/`, `./limesium-reactive-logging/src/main/resources/`, `./limesium-reactive-logging/src/test/kotlin/`, and `./limesium-reactive-logging/src/test/resources/` (relative to the workdir). **Test code is part of the analysis** and is treated both as a safety proof and as an analysis subject in its own right.
   - **Excluded:** build output and generated reports (`./limesium-reactive-logging/target/`), module documentation (consulted only as context), the pre-existing assessment being replaced, sibling modules, and pure security analysis. The repository-level `./pom.xml` and `./CLAUDE.md` were consulted for versions and conventions but are not analysis subjects.
3. Analysis environment & tools
   - **Target environment:** OpenJDK 21, Kotlin 2.4.10, Spring Boot 4.1.0 / Spring Framework 7.0.8, Spring WebFlux with Reactor; optional Kotlin coroutines 1.11.0. No virtual threads, persistence, or messaging are used by this module.
   - **Build system:** Maven 3.9.15, multi-module reactor; analysis host Oracle JDK 26.0.1 (the project compiles for Java 21)
   - **Analysis tools used:** complete manual source and test review, `rg` pattern sweeps, Git history/diff inspection, JDK/Maven version inspection, and primary framework/API documentation. The parent configures ktlint 3.0.0 and JaCoCo 0.8.12, but no defect-oriented static analyzer (detekt, SpotBugs, Error Prone, NullAway, or Sonar) is configured. No build or test was executed in this assessment because the analysis prompt requires a separate go-ahead before local verification.
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/tool-box` (absolute reference point; all relative paths refer to it)
   - **Report output path:** `./limesium-reactive-logging/docs/assessment/CODE_ANALYSIS.md` (relative to the workdir)
   - **Scope root (relative to the workdir):** `./limesium-reactive-logging/`
   - **Path convention for finding locations:** `<path relative to the workdir>:<line>` (for example, `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilter.kt:69`)

---

## 1. Executive summary

The module has a disciplined core: time and correlation generation are injectable, exchange completion is guarded exactly once, failure and cancellation are deliberately separated, pooled input buffers are released on the normal path, and the high-risk rendered-status path has focused tests. No Critical or High defect was identified. The largest current weakness is handler-side context propagation: the Reactor variant promises classpath-only MDC propagation although Spring Boot 4.1 defaults to limited propagation, while the new coroutine variant installs a three-entry `MDCContext` that replaces the complete ambient MDC map. The body-capture contract also has two correctness/scaling boundaries: error bodies rendered by the outer `WebExceptionHandler` do not traverse the response decorator, and each `DataBuffer` is copied in full even when the configured capture limit is only a few kilobytes or capture is count-only. Two uncommon but reachable lifecycle paths lose the exchange event and leave the open-exchange gauge elevated: a synchronous throw from `chain.filter(...)` and a failure while registering `beforeCommit`. The optional arrival line has a smaller fail-open gap because its logger-level check and MDC-scope construction occur outside its guard. Most issues affect observability rather than HTTP business results, which keeps their severity at Medium, but several occur deterministically once their stated configuration and path are used.

**Test verdict.** The suite is generally a strong, fast safety net: deterministic injected time/IDs and mock exchanges carry most behavior, with one real-Netty integration class and explicit cancellation/error coverage. Its pyramid is healthy, but the context-propagation proofs are materially misleading: one manually enables a hook the shipped default does not enable, and the coroutine proof clears ambient MDC before asserting only the newly added keys. The real-Netty error test enables response-body logging but never asserts the rendered error body, so it does not detect the decorator boundary.

Most significant gaps and anomalies:

- no test exercises Reactor handler MDC under Spring Boot's actual default `limited` propagation mode;
- no coroutine test proves preservation of existing trace, baggage, or application MDC entries;
- no test makes a downstream `WebFilterChain` throw synchronously or makes `beforeCommit` registration fail;
- the unhandled-error integration test does not assert captured body or body-size metrics;
- the classpath matrix (both coroutine libraries present, one missing, both missing, host filter present) is not tested as one auto-configuration system.

## 2. Scope & methodology

- **Analyzed:** all 18 production Kotlin files, the production auto-configuration resource, the module POM, all 12 Kotlin test/helper files, and the test logging resource. Test code was fully in scope.
- **Stack detected:** library module on Spring WebFlux/Reactor; one Reactor `WebFilter` and one optional coroutine `CoWebFilter`; Micrometer metrics and optional Context Propagation; no blocking production I/O, JDBC/R2DBC, ORM, transactions, broker, scheduler, or virtual-thread configuration.
- **Concurrency model:** Reactor terminal/commit callbacks can run on different threads; coroutine handlers can resume on different dispatchers; exchange hand-off fields use volatile/atomic state and body capture assumes serialized body I/O.
- **Review order:** files were ranked before deep review and then read from score 5 downward. The review traced exchange state from filter entry through decoration, downstream invocation, terminal/cancellation handling, outer error rendering, commit, metric recording, and log emission.
- **External semantic checks:** Spring Boot documents `spring.reactor.context-propagation=limited` as the default and requires `auto` for all-operator restoration; kotlinx-coroutines documents `MDCContext(contextMap)` as the explicitly installed full MDC map; Spring documents `ExceptionHandlingWebHandler` as invoking exception handlers after the delegate. These primary sources were used to kill false positives in findings 1-3.
- **Static analysis:** ktlint is style-only and JaCoCo measures coverage; no configured bug-pattern/static nullness analyzer supplements the manual pass.
- **Local verification:** none in this assessment. In accordance with the supplied prompt, a reproducer or test execution awaits explicit user approval. Existing `./limesium-reactive-logging/target/` artifacts were excluded and are not treated as verification evidence.
- **Known limitations / blind spots:** no load/heap profile was performed for body capture, no deliberately throwing response implementation was executed, and framework topology was verified from primary API documentation rather than by stepping through a live server. Sibling-module parity was assessed only through this module's lockstep tests, not by re-auditing `./limesium-servlet-logging/`.

## 3. Statistics

| Severity | Count |
|---|---:|
| 🔴 Critical | 0 |
| 🟠 High | 0 |
| 🟡 Medium | 11 |
| 🟢 Low | 1 |
| **Total** | **12** |
| Systemic patterns | 3 |

## 4. File ranking (Phase 1)

Production and configuration files:

| File | Score | Rationale |
|---|---:|---|
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/ExchangeLifecycle.kt` | 5 | Shared hot-path choreography, mutable cross-callback state, commit deferral, fail-open boundaries, exactly-once completion |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilter.kt` | 5 | Reactor entry point, signal mapping, synchronous-vs-reactive error boundary, Reactor Context |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/CoRequestLoggingWebFilter.kt` | 5 | Coroutine entry point, cancellation semantics, dispatcher/thread-local propagation, Throwable boundary |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/ExchangeLogEmitter.kt` | 5 | Final outcome/level/status decisions, body/header materialization, MDC scope, fail-open emission |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/CapturingDecorators.kt` | 5 | Pooled `DataBuffer` lifecycle, full-buffer copy, response write variants, backpressure/cancellation boundary |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/EndpointMdcContextPropagation.kt` | 4 | JVM-global accessor registry and thread-local restoration across Reactor operators |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/Exchange.kt` | 4 | Cross-thread mutable lifecycle state and two exactly-once guards |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/BoundedBodyCapture.kt` | 4 | Mutable byte accumulator, visibility hand-off, size accounting and truncation |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/CoRequestLoggingAutoConfiguration.kt` | 4 | Optional-classpath selection and ordering against the Reactor variant |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingAutoConfiguration.kt` | 4 | Conditional filter/default wiring and context-propagation initialization |
| `./limesium-reactive-logging/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 4 | Activates and orders both mutually exclusive auto-configurations |
| `./limesium-reactive-logging/pom.xml` | 4 | Defines optional coroutine/context dependencies whose combinations select runtime behavior |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/EndpointLoggingMetrics.kt` | 3 | Shared registry identity, gauge lifecycle, dynamic URI-tagged summaries |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingProperties.kt` | 3 | Binding validation, header selection/masking, body-capture and path controls |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/MdcKeys.kt` | 3 | Thread-local overlay/restoration and trace identity vocabulary |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/Traceparent.kt` | 3 | Caller-controlled parsing into trace correlation fields; deliberately lenient contract |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/EndpointLogFields.kt` | 2 | Type-checked structured-field mapping with confined rejection |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/EndpointLoggingFilter.kt` | 1 | Marker contract combining `WebFilter` and ordering |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/CorrelationIdGenerator.kt` | 1 | Trivial injectable UUID source |
| `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/NanoTimeSource.kt` | 1 | Trivial injectable monotonic time source |

Test files and resources (score reflects their weight as safety proof):

| File | Score | Rationale |
|---|---:|---|
| `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilterTest.kt` | 5 | Main Reactor behavior, error/cancel paths, exactly-once output contract |
| `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/CoRequestLoggingWebFilterTest.kt` | 5 | Coroutine parity, cancellation, error deferral, real dispatcher hop |
| `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilterIntegrationTest.kt` | 5 | Real Netty, WebFlux dispatch, pooled buffers, outer error rendering, suspend handler |
| `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/MdcContextPropagationTest.kt` | 5 | Global Reactor hook/accessors and cross-thread MDC proof |
| `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilterMetricsTest.kt` | 4 | Gauge/counter semantics and injected fail-open failures |
| `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilterBodyAndHeaderTest.kt` | 4 | Request/response tee correctness, truncation, header masking, zero-copy boundary |
| `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingAutoConfigurationTest.kt` | 4 | Spring conditional wiring and host overrides, but only one auto-configuration at a time |
| `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/EndpointLogFieldTest.kt` | 3 | Wire/type/index contract and fail-open field rejection |
| `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/EndpointLoggingReferenceConfigTest.kt` | 3 | Cross-module property/default lockstep proof |
| `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/TwinContractTest.kt` | 3 | Literal meter/MDC/masking contract pins |
| `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/AwaitingAppender.kt` | 3 | Cross-thread event capture and bounded synchronization for the integration suite |
| `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/HeaderLogPropertiesTest.kt` | 2 | Focused validation and wildcard behavior |
| `./limesium-reactive-logging/src/test/resources/logback-test.xml` | 1 | Static test logging configuration |

## 5. Findings

### 🔴 Critical

Nothing to report in this severity class.

### 🟠 High

Nothing to report in this severity class.

### 🟡 Medium

- [x] 1. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingAutoConfiguration.kt:57] {Medium} {Confidence: high} {reactive} The Reactor handler-MDC feature is not activated by the documented classpath-only opt-in
  - Symptom → cause: the auto-configuration registers three `ThreadLocalAccessor`s, and the filter writes matching values to Reactor Context, but nothing enables Reactor's automatic propagation hook. Spring Boot 4.1 defaults `spring.reactor.context-propagation` to `limited`; its documentation says `auto` is required for automatic restoration across all Reactor operators. A handler log in an ordinary operator such as `map` therefore has no guaranteed `endpoint_*` MDC despite the production KDoc promising restoration "around every operator" when the library is merely present. Primary evidence: [Spring Boot context-propagation documentation](https://docs.spring.io/spring-boot/reference/actuator/observability.html) and the documented [`limited` default](https://docs.spring.io/spring-boot/redirect.html?page=application-properties).
  - Triggering condition: always for Reactor-variant applications that rely on the advertised classpath-only opt-in while keeping Boot's default propagation mode; visibility varies by the operators through which a log statement executes.
  - Impact: application logs inside reactive handlers lose request ID/method/route correlation, producing incomplete traces and misleading cross-stack parity.
  - Fix strategy: either make activation of automatic propagation an explicit, validated prerequisite and document `spring.reactor.context-propagation=auto`, or own hook activation/lifecycle in the module. Prove the chosen contract in a Boot context without manually changing global hooks.

- [x] 2. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/CoRequestLoggingWebFilter.kt:61] {Medium} {Confidence: high} {correctness} The coroutine variant replaces the entire ambient MDC instead of adding the endpoint identity
  - Symptom → cause: `MDCContext(identity)` receives a map containing only the three `endpoint_*` keys. `MDCContext` installs the explicitly supplied map as the coroutine's complete MDC snapshot, so pre-existing `traceId`, `spanId`, baggage, tenant, or application keys disappear on every suspend-handler resumption. This diverges from `MdcScope`, which overlays only owned keys and restores previous values. Primary evidence: the official [`MDCContext` API contract](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-slf4j/kotlinx.coroutines.slf4j/-m-d-c-context/).
  - Triggering condition: every request through the coroutine variant when the incoming coroutine/thread context already carries any MDC entry not in the three-entry identity map.
  - Impact: trace correlation and other host logging context vanish inside suspend handlers; the prior map is restored after the scope, so this is data loss in emitted logs rather than a ThreadLocal leak.
  - Fix strategy: construct an additive snapshot that preserves ambient MDC and overlays only the module-owned keys, with explicit precedence for `endpoint_*`. Add preservation and restoration tests containing trace and unrelated host keys.

- [x] 3. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/ExchangeLifecycle.kt:218] {Medium} {Confidence: high (framework-topology inference)} {correctness} Unhandled error responses are rendered outside the decorated exchange and bypass response-body capture
  - Symptom → cause: body capture exists only on the mutated exchange passed down the `WebFilterChain`. Spring's outer `ExceptionHandlingWebHandler` invokes `WebExceptionHandler`s after the filtered delegate fails, using the exchange held outside that filter call; a Boot error renderer therefore writes the 500 body through the original response, not `CapturingResponseDecorator`. The commit callback still observes the final status because both responses share the delegate, but `endpoint_response_body` and the response-size sample remain absent. Primary topology evidence: [Spring Framework's `ExceptionHandlingWebHandler` contract](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/server/handler/ExceptionHandlingWebHandler.html).
  - Triggering condition: an unhandled downstream error rendered by an outer `WebExceptionHandler`, with response-body logging or response-body measurement enabled; locally handled controller/advice responses may still traverse the decorator.
  - Impact: deterministic missing response-body logs and under-reported size metrics precisely for globally rendered error responses.
  - Fix strategy: place response capture at a boundary also used by outer exception rendering, or add an ordered error-handling integration that renders through the same decorated response. If the framework boundary cannot be crossed safely, document and meter the omission instead of claiming complete response capture.

- [x] 4. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilter.kt:69] {Medium} {Confidence: high} {reactive} A synchronous downstream throw bypasses all terminal bookkeeping and permanently leaks the open-exchange gauge
  - Symptom → cause: `chain.filter(...)` is invoked before `doOnError` and `doFinally` can be attached. If a downstream `WebFilter` throws while assembling its publisher instead of returning `Mono.error`, the exception propagates synchronously; `failure` and `awaitingCommit` remain unset, the commit callback only stores status, no event is emitted, and the gauge increment at wiring is never reversed. The coroutine variant catches the analogous throw, so the variants also diverge.
  - Triggering condition: only when a downstream filter/handler adapter throws synchronously during publisher construction; ordinary reactive error signals are handled correctly.
  - Impact: lost exchange event and a monotonically inflated liveness gauge; repeated occurrences make the gauge report a false logging-pipeline outage.
  - Fix strategy: defer downstream invocation so synchronous throws become Reactor error signals before installing the existing error/cancel/finally choreography. Add a chain implementation that throws directly, distinct from returning `Mono.error`.

- [x] 5. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/ExchangeLifecycle.kt:86] {Medium} {Confidence: high (gap) / low (trigger)} {robustness} `beforeCommit` registration itself sits outside the fail-open boundary
  - Symptom → cause: the callback body is guarded, but both filters call `registerCommitCallback` after `wireOrNull` has returned and without a surrounding guard. If `response.beforeCommit(...)` throws during registration, the exception fails the request after the open gauge was incremented; no terminal callbacks are installed and the gauge/event are lost. The advertised contract says wiring and every callback are fail-open, but it protects execution of the callback, not registration.
  - Triggering condition: only with a failing/custom response facade or a framework failure during callback registration.
  - Impact: request failure caused by the logging component, lost event, and leaked open-exchange gauge.
  - Fix strategy: include callback registration in transactional fail-open wiring, and ensure a failed registration rolls back/finishes the already-open exchange before passing the original exchange downstream.

- [x] 6. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/CapturingDecorators.kt:21] {Medium} {Confidence: high} {performance} The configured body cap does not bound the tee's transient memory allocation
  - Symptom → cause: every incoming `DataBuffer` is copied into a new `ByteArray(readableByteCount)` and rewrapped in full before `BoundedBodyCapture` applies its small cap. A single large response buffer therefore allocates the complete body again even with `maxBodyBytes=4096`; count-only measurement also performs the full copy although its documentation says nothing is buffered. Under concurrent large responses, temporary memory is proportional to buffer sizes, not the configured capture limit.
  - Triggering condition: body logging or size measurement enabled, combined with large individual buffers or sufficient concurrent traffic; network/file chunks often limit the effect, while pre-aggregated byte-array responses expose it directly.
  - Impact: avoidable allocation/GC pressure and, at the extreme, process OOM despite an operator believing capture memory is bounded.
  - Fix strategy: count readable bytes without cloning them, copy only the still-needed bounded prefix into capture, and forward the original buffer with correct retain/release ownership. Verify pooled-buffer release and cancellation separately.

- [x] 7. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/ExchangeLogEmitter.kt:41] {Medium} {Confidence: high (gap) / low (trigger)} {robustness} Arrival logging's fail-open guard starts after fallible logger/MDC work and does not confine cleanup failure
  - Symptom → cause: `isInfoEnabled` and `MdcScope` construction run before the `try`; `MdcScope.close()` runs in `finally` without an outer guard. An exception from a custom/misbehaving logging backend or MDC adapter at those points escapes `logRequestStart`, which both filters call before the downstream chain. Completion emission does not share this gap because `logExchange` wraps the complete `emitExchange` call.
  - Triggering condition: only when `log-request-start=true` and the logger/MDC implementation fails during level lookup, scope creation, or restoration.
  - Impact: the optional observability line can fail the HTTP request, contradicting the module's fail-open guarantee.
  - Fix strategy: make the guard cover the complete arrival operation, including the level gate, scope creation, and scope restoration; count any confined loss as `stage=arrival` without letting cleanup mask the original failure.

- [x] 8. [./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/MdcContextPropagationTest.kt:61] {Medium} {Confidence: high} {test quality} The Reactor MDC test proves a manually enabled mode rather than the shipped default and therefore conceals finding 1
  - Symptom → cause: the test directly calls `Hooks.enableAutomaticContextPropagation()` before invoking a directly constructed filter. The production auto-configuration never calls that hook, and the auto-configuration test only checks accessor registration. The test's green result is therefore evidence for a test-only setup, while its comments attribute the same behavior to Boot merely because the library is present.
  - Triggering condition: every CI run; the test stays green even if a real application keeps Boot's default `limited` mode and loses MDC in ordinary operators.
  - Impact: false confidence in a central cross-thread correlation feature and no regression signal for the production configuration defect in finding 1.
  - Fix strategy: drive the behavior through a Boot context with the real default first, then through the explicitly supported activation mode, and assert MDC from multiple operator types across a scheduler hop.

- [x] 9. [./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/CoRequestLoggingWebFilterTest.kt:48] {Medium} {Confidence: high} {test quality} The coroutine MDC proof clears ambient context and can never detect finding 2
  - Symptom → cause: setup calls `MDC.clear()`, and the dispatcher-hop assertion reads only the three keys inserted by the filter. The test describes servlet-parity and MDC propagation but never places a pre-existing trace/application entry into the map, so replacing the entire MDC and correctly overlaying three owned entries are observationally identical to it. The real-Netty suspend test repeats the same single-key proof.
  - Triggering condition: every CI run; any implementation that preserves the injected three entries while deleting all other MDC values remains green.
  - Impact: false confidence in the coroutine variant's logging-context compatibility, concealing deterministic trace/baggage loss from finding 2.
  - Fix strategy: seed unrelated and trace MDC entries before the filter, assert all are visible together inside the foreign-thread handler, and assert exact restoration after completion, error, and cancellation.

- [x] 10. [./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilterIntegrationTest.kt:137] {Medium} {Confidence: high} {test quality} The real error-rendering test enables response capture but never asserts the error body or size
  - Symptom → cause: the integration class globally enables `log-response-body=true`, and `/it/boom` deliberately exercises Boot's outer error renderer, yet the test asserts only status, outcome, and cause. It remains green when the rendered error bytes bypass the decorator and `endpoint_response_body` is absent; response-size measuring is not enabled anywhere on this path.
  - Triggering condition: every CI run; finding 3 does not change any asserted value.
  - Impact: false confidence at the only real-server proof of the outer error path, leaving the body-capture contract unprotected.
  - Fix strategy: assert the client-visible rendered body against the captured log field and add a response-size assertion for the same route, with stable error rendering configured for deterministic comparison.

- [x] 11. [./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingAutoConfigurationTest.kt:17] {Medium} {Confidence: high} {test quality} The mutually exclusive auto-configurations are never tested as the classpath-dependent system that ships
  - Symptom → cause: `ReactiveWebApplicationContextRunner` loads only `RequestLoggingAutoConfiguration`; it never imports `CoRequestLoggingAutoConfiguration` or the actual imports resource. The integration test covers the both-libraries-present branch indirectly, but no test removes either optional coroutine library, proves Reactor fallback, asserts exactly one `EndpointLoggingFilter`, or combines either branch with a host-defined filter.
  - Triggering condition: a future change to condition names, ordering, optional dependency linkage, or the imports file; major branches can regress while the direct auto-configuration test remains green.
  - Impact: missing safety proof at the module's runtime-selection boundary can surface as startup failure, two active filters/duplicate logs, or no filter in consumer classpaths.
  - Fix strategy: test the actual two-auto-configuration set and imports resource with filtered classloaders for all meaningful dependency combinations, plus a host filter override in both selected variants.

### 🟢 Low

- [x] 12. [./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilterTest.kt:330] {Low} {Confidence: high} {test quality} The invalid-path-pattern test asserts only non-null failure; any unrelated constructor exception passes. Fix strategy: assert the parser/configuration exception type and a message fragment identifying the malformed pattern.

## 6. Systemic patterns

1. **Context-propagation assumptions are tested for presence, not for the actual activation/preservation contract (4 representative sites).** Counting basis: manual review of the two production propagation paths and their two dedicated tests. Reactor code assumes library presence implies all-operator restoration (`./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingAutoConfiguration.kt`, `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/MdcContextPropagationTest.kt`); coroutine code assumes a supplied three-entry `MDCContext` is additive (`./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/CoRequestLoggingWebFilter.kt`, `./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/CoRequestLoggingWebFilterTest.kt`). Findings 1, 2, 8, and 9 are the concrete effects.
2. **Fail-open guards do not cover the complete operation they describe (2 production sites).** Counting basis: inspection of every `try`/`catch` around logging-owned work. Commit callback execution is guarded but registration is not (`./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/ExchangeLifecycle.kt:86`), and arrival event construction is guarded only after level lookup and MDC-scope construction (`./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/ExchangeLogEmitter.kt:41`). Findings 5 and 7 are the representatives.
3. **High-value integration tests assert the successful field, not the negative/preservation boundary (3 test sites).** Counting basis: mutation thought experiment over the MDC and error-rendering tests. Manually enabled Reactor propagation, an initially empty coroutine MDC, and an error test without a body assertion all remain green under the corresponding production defects. Findings 8-10 capture the blast radius.

## 7. Remediation status (2026-08-22)

All 12 findings were re-verified against the code and confirmed before remediation; the Boot default (`ReactorProperties` defaulting `contextPropagation` to `LIMITED`, `AUTO` alone calling `Hooks.enableAutomaticContextPropagation()`) was additionally confirmed against the shipped `spring-boot-reactor-4.1.0` bytecode. The fixes landed in three commits — `1fe44e81` (MDC propagation contract), `c8e05c7e` (lifecycle fail-open gaps), `1e14768b` (bounded tee, outer-error boundary) — with the full module suite green (`mvn -pl limesium-reactive-logging verify`, 74 tests, ktlint clean).

| Finding | Resolution |
|---|---|
| 1 — classpath-only opt-in does not activate propagation | **Fixed as a validated prerequisite.** The auto-configuration's initializer now checks `spring.reactor.context-propagation` and warns loudly at startup when the mode is not `auto` (hosts enabling the hook programmatically can ignore it); KDoc on `RequestLoggingWebFilter`, `EndpointMdcContextPropagation`, and the POM document the requirement instead of promising classpath-only MDC. Hook ownership was deliberately rejected: the property is Boot's contract, and overriding an explicit `limited` would be hostile to the host. |
| 2 — `MDCContext` replaces the ambient MDC | **Fixed.** The coroutine filter now installs an additive snapshot — `MDC.getCopyOfContextMap()` overlaid with the three `endpoint_*` keys (endpoint precedence on collision), matching `MdcScope`'s overlay semantics. |
| 3 — outer error rendering bypasses response capture | **Documented and pinned as a contract boundary.** Crossing the `ExceptionHandlingWebHandler` boundary safely would require capture at a layer this `WebFilter` does not own; instead the boundary is documented on `CapturingResponseDecorator` and pinned by the real-Netty error test (client body present, `endpoint_response_body` absent), so any future capture relocation must consciously flip the pin. |
| 4 — synchronous downstream throw leaks the gauge | **Fixed.** The chain invocation runs inside `Mono.defer`, so an assembly-time throw becomes the pipeline's error signal with identical semantics to `Mono.error`; pinned by an injected-failure test asserting the deferred ERROR event and a closed gauge. |
| 5 — `beforeCommit` registration outside the fail-open boundary | **Fixed.** Registration is guarded (`stage=wiring`); a failed registration leaves the exchange unarmed (`Exchange.commitCallbackArmed`) and the error path then completes at the terminal signal instead of deferring to a callback that will never run. Pinned by a rejecting-response-facade test. |
| 6 — the body cap does not bound transient allocation | **Fixed.** The tee counts every byte, copies at most `remainingCapacity()` via a non-advancing `toByteBuffer` read, and forwards the ORIGINAL buffer downstream (undecorated ownership/release semantics); count-only mode copies nothing. Pinned by pass-through-instance and count-only tests. |
| 7 — arrival guard starts after fallible backend work | **Fixed.** The guard now covers the level gate, `MdcScope` construction, emission, and restoration (`use{}` records a close failure as suppressed); the emission scope uses the same idiom. Pinned by a throwing-TurboFilter test (request served, `stage=arrival` counted). |
| 8 — Reactor MDC test proves a manually enabled mode | **Fixed.** The test now drives Boot's real `ReactorAutoConfiguration` in a `ReactiveWebApplicationContextRunner`: one test pins that the shipped default `limited` delivers NO handler MDC in a plain `map` operator, the other proves the documented `auto` mode across a real scheduler hop. |
| 9 — coroutine MDC proof clears ambient context | **Fixed.** A preservation test seeds trace and host MDC entries, asserts them together with all three `endpoint_*` keys inside a foreign-dispatcher handler, and asserts exact restoration afterwards. |
| 10 — error-rendering test never asserts the error body | **Fixed.** The test now asserts the client-visible body exists while the event carries no `endpoint_response_body` — the honest pin of the finding-3 boundary. |
| 11 — auto-configurations never tested as the shipped system | **Fixed.** Both auto-configurations now run together: coroutine-variant selection (exactly one `EndpointLoggingFilter`), Reactor fallback under a `FilteredClassLoader` hiding `CoWebFilter`/`MDCContext`, host-filter override across both, and the imports resource pinned by content. |
| 12 — invalid-pattern test accepts any exception | **Fixed.** The test asserts `PatternParseException` and that its detailed diagnostic names the malformed pattern. |
