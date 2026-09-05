# Container guide — the servlet twin on Tomcat, Jetty and Undertow

Much of what an operator observes from `limesium-servlet-logging` is not module behavior but **engine
behavior**: when the container fires request destruction (the emission point), how it renders errors,
what it hands to a raw async cycle, and which ambient MDC the emission thread carries. This guide
documents each servlet engine individually — what the module relies on, how the engine actually behaves
at those spec corners, which integration suite pins it, and the per-engine deviations worth knowing in
production. The module-side mechanics themselves live in the [servlet guide](GUIDE.md) — and what both
twins share in the [common guide](../../docs/GUIDE.md); this document is the per-container view on top
of them.

---

## 1. The engine-facing contract

The module asks six things of a servlet engine, and these are exactly the axes on which the engines
differ:

1. **Request destruction** (`ServletRequestListener.requestDestroyed`) — the emission point
   ([§2.4 of the guide](GUIDE.md#24-emission-point-request-destruction)). The module tolerates both
   known timing models (once-late and per-dispatch) behind one exactly-once completion; the
   `endpoint.logging.exchanges.open` gauge is the liveness check against an engine that fails to fire
   destruction at all.
2. **Error rendering** — the final error body is written outside the tee (the filter skips the ERROR
   dispatch), and the emission must still see the FINAL status.
3. **Buffer semantics** of `sendError`/`reset`/`resetBuffer` — the capture follows the buffer, so
   discarded bytes must never show up in the logged body.
4. **Zero-argument `startAsync()`** — the spec words its context as holding the ORIGINAL
   request/response; whether the engine honors that decides if a raw async worker writes beside or
   through the tee.
5. **Header survival** — whether the traceless `X-Correlation-Id` echo set at filter entry survives the
   engine's error handling.
6. **Ambient MDC at emission** — whatever a tracing bridge left (or actively maintains) on the emission
   thread must not leak into the event: the emission scope owns the trace keys
   ([§5.6](GUIDE.md#56-trace-correlation)).

The module's own servlet-API surface is Servlet 3.1-level with one dormant 6.1 piece — the platform
(Spring Framework 7 / Boot 4) is what sets the Servlet 6.1 baseline; see the container-support note in
[§3.1](GUIDE.md#31-prerequisites).

---

## 2. Tomcat 11+ — the reference container

**Status:** supported; the reference implementation target. The shared integration application (`ItApp`)
lives in `RequestLoggingFilterTomcatIntegrationTest`, and the container-independent halves of the
tracing contract (Boot continuing the caller's W3C trace) are pinned only here.

**Destruction model:** ONE destruction, late — after the service, after the container's ERROR dispatch,
after async completion. The emission therefore sees final status, final headers and completed captures
without any extra choreography.

**Error path:** an unhandled exception is rethrown unchanged; Tomcat's ERROR dispatch renders the 500
through the ORIGINAL response — the event carries the final 500 and NO `endpoint_response_body`
(documented capture boundary). Bytes buffered before a `sendError` are discarded together with the
capture. The correlation echo **survives** the error dispatch.

**Raw async:** zero-argument `startAsync()` receives the originals, exactly as the spec words it — a raw
worker writes beside the tee, the body is logged as absent.

**Engine quirk (why the module keeps its own async state):** Tomcat's request facade THROWS when the
async state is queried inside `requestDestroyed` after an errored cycle — the completion listener
therefore judges "cycle still running" from module state (the exchange's atomic `CompletionState`), never
from `request.isAsyncStarted()`.

**Trace suppression:** the late destruction thread carries at most STALE bridge keys; the emission
scope's ownership removes them (pinned with a live Brave bridge).

**Suites:** `RequestLoggingFilterTomcatIntegrationTest`,
`RequestLoggingFilterTomcatTracingIntegrationTest`.

---

## 3. Jetty 12.1+

**Status:** supported. One wiring note: with several server starters on a classpath, Boot 4's
per-server auto-configuration ordering lets Jetty silently claim the server slot — a host with only one
starter is unaffected; the module's own test contexts pin their container explicitly.

**Destruction model — the defining difference:** Jetty fires `requestDestroyed` at the end of **every
dispatch**, including the initial one that merely started async. Unhandled, that emitted a bodyless
pre-completion event (`200/success` for exchanges the container answered with a 500) and stripped the
exchange from the async dispatch — found and fixed 2026-08-30. The module now skips a destruction
observed before the cycle's `onComplete`, completes at the destruction after the final dispatch, and
completes a raw `complete()`-without-dispatch through the async listener's `onComplete` backstop — all
behind one exactly-once guard.

**Consequence for tracing:** on Jetty the emission runs INSIDE the final dispatch, where the
`ServerHttpObservationFilter` scope is still open and a bridge's `traceId`/`spanId` are LIVE in the
thread's MDC. The emission scope's trace-key ownership must displace an actively maintained bridge MDC,
not just leftovers — pinned by the Jetty tracing suite.

**Everything else is spec-aligned with Tomcat:** error body outside the tee with the final status,
`sendError` discard, echo survival, originals into zero-argument `startAsync()` (raw-async body
absent).

**Suites:** `RequestLoggingFilterJettyIntegrationTest`, `RequestLoggingFilterJettyTracingIntegrationTest`.

---

## 4. Undertow 2.3 — the WildFly engine, unsupported territory

**Status:** UNSUPPORTED by the platform. Spring Framework 7's baseline is Jakarta Servlet 6.1 (with no
runtime-compatibility statement downwards), Undertow's current line implements only 6.0, and Spring
Boot 4 removed its Undertow integration — which also rules out supported WildFly deployments, since
Undertow is WildFly's servlet engine. The module side carries no blocker (its own API surface is
3.1-level, and a bytecode scan of the servlet-MVC path found no hard 6.1 invocation), so the behavior
is **pinned empirically anyway**: the Undertow suites run the shared application on a minimal
hand-rolled factory (`UndertowTestServer` — test infrastructure, not a production path) and double as
the tripwire that turns a Spring patch release adopting 6.1 API into a red build instead of a
production surprise.

**What holds:** the tees on Undertow's real streams, `sendError` discard, error rendering outside the
tee with the final status, the full MVC async lifecycle on the container-agnostic completion design,
and the trace-key suppression.

**Pinned deviation 1 — raw async is CAPTURED:** Undertow hands the CURRENT (wrapped) request/response
to zero-argument `startAsync()` where Tomcat and Jetty hand the originals. A raw async worker on
Undertow therefore writes THROUGH the tee, and `endpoint_response_body` is present for exactly the
cycles that stay absent on the other engines.

**Pinned deviation 2 — no echo on error responses:** Undertow's default error rendering REBUILDS the
response, dropping the `X-Correlation-Id` echo from error responses. This is the documented set-once
residual of `correlation-id-header` surfacing as engine behavior; the request id remains on the log
event — an operator correlating failures on Undertow-based hosts finds it there, not on the error
response's wire.

**Out of scope:** WildFly as a whole (WAR deployment, its subsystem configuration). The engine is
tested; the server is not.

**Suites:** `RequestLoggingFilterUndertowIntegrationTest`,
`RequestLoggingFilterUndertowTracingIntegrationTest`.

---

## 5. Cross-engine summary

| Concern | Tomcat 11+ | Jetty 12.1+ | Undertow 2.3 |
|---|---|---|---|
| Support status | supported (reference) | supported | unsupported territory (platform baseline), empirically pinned |
| `requestDestroyed` timing | once, after error dispatch & async completion | at the end of EVERY dispatch | engine-timed; handled by the same skip/backstop choreography |
| Emission sees final status/body | yes | yes (skip + final-dispatch/backstop completion) | yes |
| Error body in the event | absent (rendered outside the tee) | absent | absent |
| Pre-`sendError` buffered bytes | discarded | discarded | discarded |
| Echo on error responses | survives | survives | **dropped** (response rebuilt); id stays on the event |
| Raw zero-arg `startAsync()` body | absent (originals) | absent (originals) | **captured** (wrappers handed to the context) |
| Ambient bridge MDC at emission | stale keys, suppressed | LIVE scope, suppressed | engine-dependent, suppressed |
| Suites | Tomcat + TomcatTracing | Jetty + JettyTracing | Undertow + UndertowTracing |

---

## 6. Deployment notes

- **Embedded vs. WAR:** the auto-configuration registers filter and completion listener through Boot's
  `ServletContextInitializer` mechanism, which Boot's WAR support executes on external containers as
  well; `requestDestroyed` is Servlet-spec (2.4). The open-exchanges gauge is the liveness signal if a
  container's destruction behavior surprises.
- **Filter order** stays at `HIGHEST_PRECEDENCE + 10` on every engine
  ([§3.4](GUIDE.md#34-filter-order-and-other-filters), [§6.8](GUIDE.md#68-the--10-order-is-load-bearing)).
- **Path activation** matches the path WITHIN the application on every engine — a configured context
  path is stripped before matching ([§4.4](GUIDE.md#44-path-activation)).
- The generated [test-evidence page](https://inqudium.github.io/limesium/tests/test-evidence/) lists
  every per-engine test with its rationale.
