# Code & Defect Analysis: limesium-reactive-logging

> **Status: ✅ REMEDIATED (2026-08-21).** All 10 findings are resolved — fixed, corrected, documented, or
> recorded — in commit `daf44856`; the per-finding resolution is tabled in
> [section 7](#7-remediation-status-2026-08-21), including one BONUS defect found during verification
> (the lazy-MDC cross-thread race in the test appenders). Everything below this banner describes the
> state **at the analyzed commit** `9d37b731e9427e11d84b7f80d4d18aef1280afdb` and is retained unchanged
> as the audit record.

1. Identification of the codebase
   - **Repository:** `https://github.com/dhaase/tool-box.git`
   - **Commit hash:** `9d37b731e9427e11d84b7f80d4d18aef1280afdb` (full)
   - **Reference (branch/tag):** `refs/heads/main`
2. Scope of the analysis
   - **Included:** `./limesium-reactive-logging/src/main/kotlin/` and `./limesium-reactive-logging/src/test/kotlin/` (relative to the workdir). **Test code is part of the analysis** — the test-specific heuristics apply, and test classes are analysis subjects in their own right.
   - **Excluded:** build output (`./limesium-reactive-logging/target/`), documentation (consulted as context, not analyzed), sibling modules — with one deliberate exception: the twin `./limesium-servlet-logging/` was consulted as the REFERENCE the duplication must stay identical to, and identity gaps are treated as findings of THIS module.
3. Analysis environment & tools
   - **Target environment:** OpenJDK 21, Kotlin 2.4.10, Spring Boot 4.1.0 (Spring Framework 7.0.8), REACTIVE stack (Spring WebFlux / Reactor 3.8; Netty only in tests); Micrometer 1.17 core, `context-propagation` 1.2.1 as an optional dependency
   - **Build system:** Maven 3.9.15 (multi-module reactor, `spring-boot-starter-parent` 4.1.0)
   - **Analysis tools used:** manual review (full read of every main-source file, targeted read of every test class), `grep` sweeps, side-by-side comparison with the servlet twin; ktlint (style only) via the parent build — no bug-pattern tool, so the manual pass carries the weight. No new build/test execution for this read-only analysis; the suite is known green at this commit (49 tests, `mvn verify`).
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/tool-box` (absolute reference point; all relative paths refer to it)
   - **Report output path:** `./limesium-reactive-logging/docs/assessment/CODE_ANALYSIS.md` (relative to the workdir)
   - **Scope root (relative to the workdir):** `./limesium-reactive-logging/`
   - **Path convention for finding locations:** `<path relative to the workdir>:<line>` (e.g. `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilter.kt:114`)

---

## 1. Executive summary

The module is the deliberately duplicated WebFlux twin of `limesium-servlet-logging`, built AFTER that twin's defect analysis — and it shows: the wiring is fail-open from the start, the emission guard covers everything behind the exactly-once CAS, capture visibility is self-owned (`@Volatile` publish), and the commit-deferred error emission solves the final-status problem the servlet twin needed a redesign for. No Critical or High finding was identified. The Medium findings cluster in one place the servlet remediation could not have anticipated: **reactive callback bodies are not fail-open-guarded**. The `beforeCommit` action and the `doFinally` block run unguarded — an exception in the commit callback would surface inside the RESPONSE COMMIT chain (request-affecting, the one thing the fail-open contract forbids), and in both places a failure would neither be confined nor counted. The second Medium is a genuine twin-parity gap: the reactive **arrival line is emitted without any MDC scope**, so structured encoders see `endpoint_request_id` on servlet arrival lines but not on reactive ones. Third, **zero-copy responses bypass the response tee** (the decorator inherits the file-writing path untouched), so a file-serving handler logs no body and records no size although bytes flowed. The deferred-commit race between the terminal signal and the commit callback was examined closely and is correctly closed (state ordering plus the exactly-once CAS cover all interleavings); the never-committing error residual is documented, intentional, and observable on the gauge — not a finding. The Low tier collects duplication-drift surfaces that the cross-module lockstep tests do NOT cover (meter names, MDC key values, the mask fingerprint), test-side global-state remarks, and small edges.

**Test verdict.** (1) *Reliability as a safety net:* high. Deterministic throughout (injected time, pinned ids, mock exchanges driven synchronously, semaphore-awaited integration events, no mock library); the suite pins the two hardest behaviors — the commit-deferred 500 and the cancelled disposition — both in unit form and (for the 500) against real Netty, and the cross-reactor lockstep tests turn configuration and field identity with the twin into build breakage. (2) *Pyramid:* healthy — ~40 unit tests carry the load, one real-Netty integration class on top, one genuinely concurrent propagation test with a real thread hop. (3) *Most significant gaps:*
- the **`beforeCommit`/`doFinally` failure paths have no test** (ties to finding 1 — there is also no guard to test yet);
- the **arrival line's MDC absence** is uncaught because no test asserts arrival-line MDC (ties to finding 2);
- **zero-copy responses** are untested (ties to finding 3);
- global JVM state (`ContextRegistry` accessors, Reactor hooks enabled by the Boot IT context) is never fully undone across the module's test JVM — currently benign, order-dependent in principle;
- one test sets a logger to ERROR mid-test without restoring it (disjoint logger name, so contained).

## 2. Scope & methodology

- **Analyzed:** all 14 production files (~1.5 kLOC) — read completely; all 10 test classes — read completely, as analysis subjects. The twin's sources served as the identity yardstick.
- **Stack detected:** Spring Boot 4.1.0 / Spring Framework 7.0.8, WebFlux (Reactor), no coroutines, no persistence, no messaging; `spring-web` + `reactor-core` at compile scope, Netty via test-scoped starter; `io.micrometer:context-propagation` optional (classpath presence = feature opt-in).
- **Static analysis:** ktlint only (style). Noted as a data point.
- **Local verification:** none performed within this analysis; suite known green at this commit (49 tests).
- **Known limitations / blind spots:** Reactor-internal semantics (`doFinally` exception handling, `AbstractServerHttpResponse` commit-state ordering) were assessed from framework knowledge and the observable contract, not a Reactor/Spring source audit; finding 1's trigger probability carries that uncertainty. The duplication comparison covered contracts and behavior, not a line-by-line diff of the copied files.

## 3. Statistics

| Severity | Count |
|---|---|
| 🔴 Critical | 0 |
| 🟠 High | 0 |
| 🟡 Medium | 3 |
| 🟢 Low | 7 |
| **Total** | **10** |
| Systemic patterns | 2 |

## 4. File ranking (Phase 1)

Production code:

| File (relative to `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/`) | Score | Rationale |
|---|---|---|
| `RequestLoggingWebFilter.kt` | 5 | Entry point; signal/commit choreography, exactly-once completion, wiring fail-open, include matching |
| `ExchangeLogEmitter.kt` | 5 | Emission on foreign callback threads; level/outcome matrix incl. `cancelled`; null-status handling |
| `CapturingDecorators.kt` | 4 | DataBuffer tee: read/release/rewrap on pooled buffers; write-path variants |
| `EndpointMdcContextPropagation.kt` | 3 | JVM-global registry mutation; thread-local bridging |
| `EndpointLoggingMetrics.kt` | 3 | Meter registration semantics, dynamic tags (duplicated contract) |
| `RequestLoggingProperties.kt` (incl. `HeaderLogProperties`) | 3 | Selection/masking/validation (duplicated contract) |
| `BoundedBodyCapture.kt` | 3 | Cross-thread state; visibility self-owned since the twin's remediation |
| `Exchange.kt` | 2 | State record, volatiles, two CAS guards |
| `MdcKeys.kt` / `Traceparent.kt` | 2 | MDC vocabulary, header parsing |
| `RequestLoggingAutoConfiguration.kt` | 2 | Conditional wiring incl. classpath-gated propagation config |
| `EndpointLogFields.kt` | 2 | Shape-owning enum (duplicated contract, lockstep-guarded) |
| `NanoTimeSource.kt`, `CorrelationIdGenerator.kt` | 1 | Trivial functional interfaces |

Test code (weight as safety proof): `RequestLoggingWebFilterTest` 5 (format identity, deferred-commit, cancelled), `RequestLoggingWebFilterIntegrationTest` 4 (real Netty incl. rendered-500), `MdcContextPropagationTest` 4 (real thread hop, global state discipline), `RequestLoggingWebFilterMetricsTest` 3, `RequestLoggingWebFilterBodyAndHeaderTest` 3, `EndpointLogFieldTest` / `EndpointLoggingReferenceConfigTest` 3 (cross-reactor identity guards), `RequestLoggingAutoConfigurationTest` 3, `HeaderLogPropertiesTest` 2, `AwaitingAppender` 2.

## 5. Findings

### 🟠 High

Nothing to report in this severity class.

### 🟡 Medium

- [x] 1. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilter.kt:114-121,126-156] {Medium} {Confidence: high (gap) / low (trigger)} {robustness} The reactive callback bodies are not fail-open-guarded
  - Symptom → cause: the `beforeCommit` action (`committedStatus` read, deferred `complete`) and the `doFinally` block (template read, breadcrumb, completion dispatch) run without a confining catch. An exception in the `beforeCommit` action surfaces INSIDE the response-commit chain — potentially failing the commit, i.e. affecting the request, the one outcome the module's fail-open contract categorically forbids; an exception in `doFinally` is rethrown into Reactor's signal propagation. In both cases the loss is neither confined nor counted on the fail-open meter. This is the reactive edition of the twin's "guard starts after fallible work" pattern, in the two places its remediation did not exist yet.
  - Triggering condition: only edge case — a response facade whose `statusCode`/attribute access throws at commit/terminal time, or a failure inside the breadcrumb path.
  - Impact: worst case a disturbed response commit (request-affecting); otherwise an uncounted lost emission.
  - Fix strategy: wrap both callback bodies in the same InterruptedException-aware confinement the emitter uses, counting `wiring`/`emission` respectively; keep the exactly-once CAS outside.

- [x] 2. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/ExchangeLogEmitter.kt:39-70] {Medium} {Confidence: high} {correctness} The arrival line is emitted without an MDC scope — a twin-parity gap
  - Symptom → cause: the servlet twin's arrival line runs inside the chain's `MdcScope`, so structured encoders emit `endpoint_request_id`/`endpoint_method`/`endpoint_route` as MDC fields on it. The reactive `logRequestStart` opens no scope — its events carry the identity only inline in the message. Output is therefore NOT identical across the twins for `log-request-start=true`, violating the module's core requirement; no test asserts arrival-line MDC, which is why it slipped.
  - Triggering condition: always, when the arrival line is enabled.
  - Impact: encoder-side field loss on arrival lines; index queries keying on `endpoint_request_id` miss them in reactive services only.
  - Fix strategy: open the same `MdcScope` (incl. trace overlay) around the arrival emission as around the completion emission, and assert `mdcPropertyMap` in both twins' arrival tests.

- [x] 3. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/CapturingDecorators.kt:50-64] {Medium} {Confidence: high} {correctness} Zero-copy responses bypass the response tee
  - Symptom → cause: `CapturingResponseDecorator` overrides `writeWith(Publisher)` and `writeAndFlushWith`, but a handler serving files uses the `ZeroCopyHttpOutputMessage` path (`writeWith(Path, pos, count)`), which the decorator inherits untouched — the bytes go to the client without passing the tee. The exchange then logs no `endpoint_response_body` and records no size sample although a (possibly large) body flowed; under `measure-response-body-size` the distribution silently under-reports exactly the largest responses.
  - Triggering condition: only file-/resource-serving handlers on zero-copy-capable servers (Netty).
  - Impact: missing body field (arguably fine) and WRONG size metric (misleading absence).
  - Fix strategy: override the zero-copy variant to at least COUNT the byte range into the capture (count-only, no buffering), or document the boundary in README and field KDoc.

### 🟢 Low

- [x] 4. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/CapturingDecorators.kt:42] {Low} {Confidence: medium} {robustness} `getBody()` returns a fresh tee on every call — a second subscription (unusual, but legal for the owner of the request) would double-count into the same capture; the servlet twin's stream is naturally once-only. Fix strategy: memoize the decorated flux or document.
- [x] 5. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/Traceparent.kt:16-31] {Low} {Confidence: high} {correctness} Version and flags fields are not validated (a `traceparent` with an invalid version like `ff` is accepted). Deliberately mirrors `ExchangeDiaryLogging.parseTraceparent` — consistency was chosen over strictness; noted so the choice is visible. Fix strategy: none, or tighten both implementations together.
- [x] 6. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/EndpointMdcContextPropagation.kt:41-49] {Low} {Confidence: high} {resources} Accessors are registered against the JVM-global `ContextRegistry` and never deregistered. Fine for an application (the registry is meant to be global); in the module's own test JVM it is cross-test global state, and the Boot integration context additionally enables the automatic-propagation hooks without anything disabling them afterwards — currently benign (no test depends on their absence), order-dependent in principle. Fix strategy: none in production code; keep test assertions independent of hook state (they are today).
- [x] 7. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/EndpointLoggingMetrics.kt + MdcKeys.kt + RequestLoggingProperties.kt (mask)] {Low} {Confidence: high} {maintainability} Duplicated contracts WITHOUT a cross-module guard: the lockstep tests pin configuration keys/defaults and field wire names against the twin, but the meter names, the MDC key values, and the masking fingerprint format are duplicated with no test that compares them across modules — a divergence there would ship green. Fix strategy: extend one lockstep test with literal assertions on those constants (both modules already assert their own literals; a shared literal source of truth in the servlet docs would close it).
- [x] 8. [./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilterMetricsTest.kt:192] {Low} {Confidence: high} {test quality} The gated-level test sets `http-exchange-reactive-metrics-gated` to ERROR and never restores it. Contained by the disjoint logger name (the module's own isolation idiom), but the only place in either twin where a level mutation has no cleanup. Fix strategy: reset in a finally or use the standard setUp/tearDown pair.
- [x] 9. [./limesium-reactive-logging/src/test/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilterIntegrationTest.kt] {Low} {Confidence: high} {test quality} Integration coverage gaps: no traceparent round-trip, no cancelled exchange, no zero-copy response against real Netty. The first two are unit-covered; zero-copy is uncovered anywhere (ties to finding 3). Fix strategy: add a file-serving IT route once finding 3's direction is decided.
- [x] 10. [./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingWebFilter.kt:112-121] {Low} {Confidence: medium} {concurrency} In the terminal/commit race, `complete()` from the `doFinally` side can emit before the commit callback assigned `committedStatus`; the emitter's fallback to `response.statusCode` yields the correct rendered value at that point, so the outcome is right — but the correctness rests on the fallback, not on the handoff. Noted as an observation with the reasoning recorded; the exactly-once guard itself covers all interleavings. Fix strategy: none required; a comment linking the fallback to this race would preserve the reasoning.

## 6. Systemic patterns

1. **Unguarded reactive callback bodies (2 sites; counted by reading the filter's callback registrations).** The fail-open discipline that covers wiring and emission stops at the boundary of Reactor callbacks: `beforeCommit` (`RequestLoggingWebFilter.kt:114-121`) and `doFinally` (`:126-156`) run bare. Finding 1 is the representative; the remediation is one confinement per site — the same boundary move the servlet twin's assessment prescribed for its pre-guard sections.
2. **Duplicated contracts without cross-module guards (3 surfaces; by comparison with the twin's lockstep coverage).** Configuration keys/defaults and field wire names are identity-guarded across the reactor; meter names, MDC key values, and the mask fingerprint are duplicated unguarded (`EndpointLoggingMetrics.kt`, `MdcKeys.kt`, `HeaderLogProperties.mask`). Finding 7 is the representative.


---

## 7. Remediation status (2026-08-21)

All findings addressed in commit `daf44856` (follow-up session; the analysis itself changed no code).

| Finding | Resolution |
|---|---|
| 1 (callbacks unguarded) | **Code fix** — `beforeCommit` and `doFinally` bodies confined (emission/wiring stages); the terminal guard still completes the exchange unless the deferral was armed; proven by decorator-injected failures |
| 2 (arrival line without MDC) | **Code fix** — arrival emission opens the same `MdcScope` (incl. trace overlay) as the completion; asserted in the arrival test |
| 3 (zero-copy bypass) | **Analysis corrected + documented + pinned** — the decorator not implementing `ZeroCopyHttpOutputMessage` makes writers fall back to the buffered path, so bytes flow THROUGH the tee; the real trade-off (zero-copy lost while capturing) is documented, the mechanism pinned by `isNotInstanceOf` test |
| 4 (getBody re-subscription) | **Documented** in the decorator KDoc |
| 5 (traceparent leniency) | **Documented** — consistency with the web-client routine; tighten both or neither |
| 6 (global registry/hooks) | **Accepted** — the registry is global by design; registration is idempotent; tests stay hook-independent |
| 7 (unguarded duplicated contracts) | **Test added** — `TwinContractTest` in BOTH modules literal-pins meter names, MDC key values and the masking fingerprint |
| 8 (unrestored logger level) | **Test fix** — restored in a `finally` |
| 9 (IT gaps) | **Test added** — traceparent round-trip against real Netty; cancelled stays unit-covered; zero-copy covered via the mechanism pin |
| 10 (status-fallback race reasoning) | **Documented** — comment at the deferral site links the fallback to the interleaving |

**Bonus defect (would have been finding 11), found while verifying finding 9:** logback's
`LoggingEvent.getMDCPropertyMap()` captures the MDC **lazily on the first access, from the calling
thread** (verified in the 1.5.34 bytecode; the constructor does not capture). The `AwaitingAppender`s
handed events to the awaiting test thread racing that first access — a test-thread win captured the test
thread's empty MDC (one full-suite flake observed, `{}` where five entries were expected). Both twins'
appenders now call `prepareForDeferredProcessing()` on the emitting thread — logback's own mechanism for
cross-thread event inspection. **Follow-up noted:** the sync-bridge MDC tests (`OutboundMdcPropagationTest`,
`InboundMdcDispatcherHopTest`) inspect events across threads with the same latent pattern.

Suite after remediation, both twins verified twice: 89 + 57 tests, 0 failures (`mvn verify` incl. ktlint).
