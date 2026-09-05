# limesium-servlet-logging — Guide

One structured `endpoint_*` log line per HTTP exchange in a Spring Boot **servlet** (Tomcat, Jetty)
application, with the exchange identity in the MDC while the request is handled. This module is the
**reference implementation** of the endpoint-logging family; its WebFlux twin
[`limesium-reactive-logging`](../../limesium-reactive-logging/README.md) shares the message format, the
field family, the `endpoint-logging.*` configuration and the meters.

This guide holds what the **servlet stack decides**: how the filter and its two registrations get into
the container, request destruction as the emission point, the async lifecycle, the chain-wide MDC, the
stream and writer tees, and the servlet-only edge cases. Everything the two modules share — the exchange
line, the shared architecture, dependency and encoder setup, the whole configuration namespace, the
field family, the meters and the trace contract — is the [common guide](../../docs/GUIDE.md) and is not
repeated here. Both together are the long-form companion to the module [README](../README.md).
Everything here is derived from the code under `src/main/kotlin/eu/inqudium/limesium/servlet/logging/`;
when the two disagree, the code wins.

## Table of contents

1. [Introduction](#1-introduction)
   1. [What is specific to the servlet stack](#11-what-is-specific-to-the-servlet-stack)
   2. [What the module deliberately does not do](#12-what-the-module-deliberately-does-not-do)
2. [Architecture](#2-architecture)
   1. [Component overview](#21-component-overview)
   2. [Auto-configuration and registration](#22-auto-configuration-and-registration)
   3. [Lifecycle of one exchange](#23-lifecycle-of-one-exchange)
   4. [Emission point: request destruction](#24-emission-point-request-destruction)
   5. [Async exchanges](#25-async-exchanges)
   6. [The body tee](#26-the-body-tee)
   7. [MDC coverage](#27-mdc-coverage)
   8. [Fail-open stages](#28-fail-open-stages)
3. [Using it in a foreign project](#3-using-it-in-a-foreign-project)
   1. [Prerequisites](#31-prerequisites)
   2. [Automatic wiring](#32-automatic-wiring)
   3. [Manual wiring](#33-manual-wiring)
   4. [Filter order and other filters](#34-filter-order-and-other-filters)
   5. [Replacing the filter bean](#35-replacing-the-filter-bean)
4. [Configuration on the servlet stack](#4-configuration-on-the-servlet-stack)
   1. [Property notes](#41-property-notes)
   2. [Header selection](#42-header-selection)
   3. [Body rules](#43-body-rules)
   4. [Path activation](#44-path-activation)
   5. [Logger levels](#45-logger-levels)
5. [Metrics and observation on the servlet stack](#5-metrics-and-observation-on-the-servlet-stack)
   1. [Log fields](#51-log-fields)
   2. [MDC keys](#52-mdc-keys)
   3. [Levels and outcomes](#53-levels-and-outcomes)
   4. [Meters](#54-meters)
   5. [Reading the meters together](#55-reading-the-meters-together)
   6. [Trace correlation](#56-trace-correlation)
6. [Special characteristics](#6-special-characteristics)
   1. [Duration is request occupancy](#61-duration-is-request-occupancy)
   2. [Container error rendering bypasses the response tee](#62-container-error-rendering-bypasses-the-response-tee)
   3. [Buffer-clearing operations discard the capture](#63-buffer-clearing-operations-discard-the-capture)
   4. [Raw `startAsync()` bypasses the tee](#64-raw-startasync-bypasses-the-tee)
   5. [Async started and completed inside the chain](#65-async-started-and-completed-inside-the-chain)
   6. [Request charset: log rendering vs. the servlet contract](#66-request-charset-log-rendering-vs-the-servlet-contract)
   7. [Writer fidelity and `checkError()`](#67-writer-fidelity-and-checkerror)
   8. [The `+ 10` order is load-bearing](#68-the--10-order-is-load-bearing)
7. [Appendix](#7-appendix)
   1. [File map](#71-file-map)
   2. [Related documents](#72-related-documents)

---

## 1. Introduction

### 1.1 What is specific to the servlet stack

What every Limesium module does for an inbound exchange — identity per ADR-0002, the optional arrival
line, the duration, the body tee, the header selection, the `traceparent` parse, exactly one completion
event, six meters, all fail-open — is the [common guide's §1.1](../../docs/GUIDE.md#11-what-the-modules-do).
`limesium-servlet-logging` realises it as a Spring Boot auto-configured `OncePerRequestFilter` plus a
`ServletRequestListener`, and adds what only a thread-per-request stack can offer:

- the `endpoint_request_id`, `endpoint_method` and `endpoint_route` keys are in the **MDC for the whole
  filter chain** — and on the Spring MVC async worker thread — so every application log line downstream
  is correlatable without any propagation setup ([§2.7](#27-mdc-coverage));
- the completion event is emitted at **request destruction** — after the container's error dispatch and
  after async completion, so the logged status is the response's final one
  ([§2.4](#24-emission-point-request-destruction));
- Spring MVC async exchanges (`Callable`, `WebAsyncTask`, `DeferredResult`, `suspend` controllers) are
  followed through the async dispatch, and a container timeout becomes the `timeout` disposition
  ([§2.5](#25-async-exchanges), [§5.3](#53-levels-and-outcomes));
- every event carries `endpoint_async`, telling whether the chain returned with async processing
  started ([§5.1](#51-log-fields));
- the body tee consists of servlet stream/reader and stream/writer wrappers, whose capture follows the
  response buffer ([§2.6](#26-the-body-tee), [§6.3](#63-buffer-clearing-operations-discard-the-capture)).

### 1.2 What the module deliberately does not do

Beyond the non-goals shared by both modules
([common guide §1.2](../../docs/GUIDE.md#12-what-the-modules-deliberately-do-not-do)), one is specific
to this stack:

- **No MDC on application-owned threads.** `DeferredResult` producers and raw servlet async workers run
  on threads neither the container nor Spring routes through this module ([§2.7](#27-mdc-coverage)).

---

## 2. Architecture

### 2.1 Component overview

Fifteen Kotlin files in one package, `eu.inqudium.limesium.servlet.logging`, in five layers:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Auto-configuration                                                           │
│   RequestLoggingAutoConfiguration                                            │
│   RequestLoggingProperties · HeaderLogProperties                             │
├──────────────────────────────────────────────────────────────────────────────┤
│ Servlet lifecycle                                                            │
│   RequestLoggingFilter (OncePerRequestFilter)                                │
│     ├─ ExchangeCompletionListener (ServletRequestListener)  ◀ emission       │
│     ├─ AsyncOutcomeMarker (AsyncListener)                                    │
│     └─ EndpointMdcCallableInterceptor (CallableProcessingInterceptor)        │
├──────────────────────────────────────────────────────────────────────────────┤
│ State and emission                                                           │
│   Exchange / AsyncDisposition                                                │
│   ExchangeLogEmitter  ──▶  EndpointLogField                                  │
│   EndpointLoggingMetrics                                                     │
├──────────────────────────────────────────────────────────────────────────────┤
│ Capture                                                                      │
│   CapturingRequestWrapper · CapturingResponseWrapper · BoundedBodyCapture    │
├──────────────────────────────────────────────────────────────────────────────┤
│ Cross-cutting                                                                │
│   MdcKeys · TraceMdcKeys · MdcScope                                          │
│   NanoTimeSource · CorrelationIdGenerator · reportQuietly                    │
└──────────────────────────────────────────────────────────────────────────────┘
```

The state, emission, capture and cross-cutting components carry the same names and contracts in both
modules — which of them are one shared class and which are per-stack twins is the
[common guide's §2.1](../../docs/GUIDE.md#21-shared-components). The servlet-side responsibilities:

| Class | Responsibility |
|---|---|
| `RequestLoggingAutoConfiguration` | Registers the filter bean, its `FilterRegistrationBean` (order `HIGHEST_PRECEDENCE + 10`), the `ServletListenerRegistrationBean` for the completion listener, and the default `NanoTimeSource` / `CorrelationIdGenerator` / `HeaderValueMasker`. |
| `RequestLoggingProperties` | The `endpoint-logging.*` binding, validated in `init`. `HeaderLogProperties` (shared, limesium-common — [common guide §6.4](../../docs/GUIDE.md#64-shared-code-limesium-common-inlined-by-shade)) is one header section with `includes` / `excludes` / `masked` / `unmasked` and the masking fingerprint. |
| `RequestLoggingFilter` | Owns the **servlet side**: path activation, fail-open wiring, identity resolution (`traceparent` first, correlation header on traceless exchanges) with the traceless echo, the tee wrappers, the chain-wide `MdcScope`, the async dispatch pass, the breadcrumb, the handoff to destruction. |
| `Exchange` / `AsyncDisposition` / `AsyncOutcomeMarker` | Per-exchange state from entry to emission; the async disposition as one atomic value with built-in precedence; the `AsyncListener` that marks timeout/error. |
| `EndpointMdcCallableInterceptor` | Restores the `endpoint_*` MDC on the Spring MVC `Callable`/`WebAsyncTask` worker thread. |
| `ExchangeLogEmitter` | Builds and emits the arrival line and the completion event; resolves level, outcome and cause (the async disposition included); records body sizes; opens the emission `MdcScope` with trace ownership. |
| `EndpointLogField` | The wire names and the exact JVM type of each structured field — this module's enum carries the `endpoint_async` field and the `timeout` outcome. |
| `EndpointLoggingMetrics` | The six meters, with `timeout` in the `outcome` tag vocabulary. |
| `CapturingRequestWrapper` / `CapturingResponseWrapper` | The servlet stream/reader and stream/writer tees. |
| `BoundedBodyCapture` | The bounded capture target; count-only mode with limit `0`; the request-side read state (`BodyReadState`); single-writer/late-reader visibility via a volatile total. |
| `MdcScope` | Puts identity (and, for the emission, trace keys) into the MDC and restores the previous values on close — around the chain, the async dispatch and the emission. |

### 2.2 Auto-configuration and registration

`RequestLoggingAutoConfiguration` is listed in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and is conditional on
`@ConditionalOnWebApplication(type = SERVLET)` and `endpoint-logging.enabled` (default `true`). It
registers:

| Bean | Condition | Purpose |
|---|---|---|
| `NanoTimeSource` | `@ConditionalOnMissingBean` | `NanoTimeSource.SYSTEM` |
| `CorrelationIdGenerator` | `@ConditionalOnMissingBean` | `CorrelationIdGenerator.DEFAULT` (counting generator: random per-instance base-36 prefix + counter, 21 chars) |
| `HeaderValueMasker` | `@ConditionalOnMissingBean` | `HeaderValueMasker.DEFAULT` (the `length:hash` fingerprint); the one bean both twins mask with |
| `RequestLoggingFilter` | `@ConditionalOnMissingBean` | the filter, built from the bound properties and the host's `MeterRegistry` (`ObjectProvider`; private `SimpleMeterRegistry` without one) |
| `FilterRegistrationBean<RequestLoggingFilter>` | always | order `Ordered.HIGHEST_PRECEDENCE + 10`; referencing the filter bean keeps Boot from also auto-registering the bare `Filter` |
| `ServletListenerRegistrationBean<ServletRequestListener>` | always | `filter.exchangeCompletionListener()` — the emission point |

Because the filter is its own bean, a host can replace it while keeping the registration and the
listener ([§3.5](#35-replacing-the-filter-bean)); how the registrations work and when the host registers by hand
is [§3.2](#32-automatic-wiring) and [§3.3](#33-manual-wiring). The servlet API is a `provided`
dependency; the host's container supplies it.

### 2.3 Lifecycle of one exchange

The UML activity diagram [`activity-diagram.svg`](activity-diagram.svg) shows the complete flow of one
exchange — activation, wiring, the chain with its MDC scope, the async branch (marker, worker MDC,
re-dispatch), the emission at request destruction and the exactly-once guard. The following sketch is
the condensed form:

```
client ──▶ Tomcat ──▶ (ServerHttpObservationFilter @ HIGHEST+1, if tracing) ──▶ RequestLoggingFilter @ HIGHEST+10
                       │
                       ├─ isAsyncDispatch?  ──yes──▶ filterAsyncDispatch (existing exchange, see §2.5)
                       │
                       ├─ shouldNotFilter(requestURI)?  ──yes──▶ chain (untouched pass-through)
                       │
                       ├─ wireExchange  ──throws──▶ chain (fail-open, stage=wiring)
                       │     • request id: traceparent trace id, else header or generated (ADR-0002);
                       │       echoed on the response only when traceless
                       │     • body captures + wrappers if logging OR measuring is on
                       │     • request headers selected and masked (multi-value, comma-joined)
                       │     • traceId/parentSpanId parsed from the traceparent header
                       │     • startNanos read from NanoTimeSource
                       │     • exchange stored as request attribute; gauge exchanges.open += 1
                       │
                       ├─ registerAsyncMdcPropagation   (EndpointMdcCallableInterceptor via WebAsyncUtils)
                       ├─ MdcScope(requestId, method, path) opened   (fail-open: no scope on failure)
                       ├─ logRequestStart if enabled
                       │
                       └─ try     chain.doFilter(wrappedRequest, wrappedResponse)
                          catch   exchange.failure = e; rethrow
                          finally pathTemplate ← MVC's BEST_MATCHING_PATTERN_ATTRIBUTE
                                  if request.isAsyncStarted: asyncStarted = true; addListener(AsyncOutcomeMarker)
                                  WARN breadcrumb on the module's own logger if failure != null
                                  MdcScope.close()   (guarded separately)

 … container error dispatch / async completion …

 Tomcat ──▶ ExchangeCompletionListener.requestDestroyed
              exchange ← request attribute (removed)      → guards the gauge against double decrement
              gauge exchanges.open -= 1
              ExchangeLogEmitter.logExchange(exchange)     → exactly-once CAS, then the event
```

The emitter computes duration, reads the **final** `response.status`, classifies level/outcome/cause,
records body sizes, gates on the logger level, opens the emission `MdcScope` (with trace ownership, see
[§5.6](#56-trace-correlation)), selects the response headers, decodes the captured bodies and writes one
event.

### 2.4 Emission point: request destruction

Emitting in the filter's `finally` would report the **pre-error-dispatch** status: a crashed exchange
would log `-> 200` although the container afterwards rendered the 500 that became the response's
final status. The event is
therefore emitted from `ServletRequestListener.requestDestroyed` — the moment the request finally goes
out of scope:

- after the service method returned,
- after the container's **ERROR dispatch** (Boot's error page, `sendError` handling),
- for an async exchange, after **async completion**.

So the logged status, response headers and captures are final and race-free. Two consequences:

1. `endpoint_duration_ms` measures **request occupancy** including error rendering and async waiting,
   not bare chain time ([§6.1](#61-duration-is-request-occupancy)).
2. Everything rests on the container firing `requestDestroyed` for every request the filter saw. The
   gauge `endpoint.logging.exchanges.open` makes that assumption measurable
   ([§5.5](#55-reading-the-meters-together)); the exactly-once CAS on `Exchange.logged` backstops
   container quirks.

When the chain throws, a short **WARN breadcrumb** is logged immediately in the `finally` on the module's
own logger (`eu.inqudium.limesium.servlet.logging.RequestLoggingFilter`) — the exception's `toString`, no stack
trace — so the failure is visible the moment it happens, while the full ERROR event with the cause
follows at destruction. The breadcrumb is deliberately not on the exchange logger (one event per
exchange is that stream's contract) and deliberately WARN (the ERROR belongs to the full event). The
exception itself is rethrown **unchanged**.

### 2.5 Async exchanges

Spring MVC async controllers (`Callable`, `WebAsyncTask`, `DeferredResult`, `suspend` functions via the
MVC bridge) return from the chain with `request.isAsyncStarted == true`. The module handles that in three
places:

**Marking (`AsyncOutcomeMarker`).** An `AsyncListener` registered in the filter's `finally` marks the
exchange's `AsyncDisposition` — `TIMED_OUT` on `onTimeout`, `ERRORED` on `onError` — and keeps the
event's throwable as the cause when the container supplied one. The disposition is **which callback
occurred**, never inferred from throwable presence (the servlet API permits `onError` without a throwable
and `onTimeout` with one). Precedence is built into the value: `TIMED_OUT` is absorbing (set
unconditionally), `ERRORED` replaces only `NONE` — an atomic transition, because the container does not
promise that both callbacks run on one thread. On a re-entrant `startAsync` the container drops
listeners, so `onStartAsync` re-registers the marker.

**The async dispatch pass.** `shouldNotFilterAsyncDispatch()` is `false`: when the container
re-dispatches the completed async cycle (Spring MVC renders the result — or rethrows the failure — in
that dispatch), the filter runs **again on the existing exchange**: no re-wiring, no second correlation
id, no second gauge increment. The pass only opens the chain-wide `MdcScope` around the dispatch and
records an exception propagating out of it as the exchange's failure — so an async handler failure logs
ERROR with its cause, exactly like the synchronous equivalent, and the rendering phase's log lines carry
the identity. A handled async exception (an `@ExceptionHandler` in the dispatch) never propagates and is
classified by its status — parity with the sync path.

**Worker MDC (`EndpointMdcCallableInterceptor`).** See [§2.7](#27-mdc-coverage).

The emission still happens at request destruction, but containers differ in WHEN that fires: Tomcat
once, after async completion; Jetty at the end of **every dispatch**, including the initial one that
merely started async. A destruction observed before the cycle's `onComplete` is therefore skipped
(judged from module state — Tomcat's request facade throws when its async state is queried inside
`requestDestroyed` after an errored cycle), the destruction after the completed cycle emits, and a raw
`complete()` without a further dispatch is completed by the async listener's `onComplete` backstop —
all behind one exactly-once guard. The Jetty integration test pins this choreography per container
([CONTAINERS.md](CONTAINERS.md)). The emitter reads `asyncStarted` (→ `endpoint_async: true`) and the
disposition ([§5.3](#53-levels-and-outcomes)).

### 2.6 The body tee

The principle — a passive tee into a bounded capture that mirrors what the application consumed, never
what the client transmitted — is the
[common guide's §2.3](../../docs/GUIDE.md#23-the-body-tee-capture-mirrors-consumption). On this stack
the tee consists of two servlet wrappers:

- `CapturingRequestWrapper` overrides `getInputStream()` **and** `getReader()` (the base class would
  otherwise hand out the original request's reader and bypass the tee). Both are served from one
  delegate stream, so the wrapper reproduces the servlet either-or contract itself. Bytes are copied as
  the application reads them; an unread body is logged as absent.
- `CapturingResponseWrapper` overrides `getOutputStream()` and `getWriter()`. Every byte is forwarded to
  the real response **first** and copied second, so commit semantics, streaming and content length are
  those of an unwrapped response; nothing is withheld, so — unlike `ContentCachingResponseWrapper` —
  nothing has to be copied back, which is what makes it safe for async completion.
- `BoundedBodyCapture` is the target. Visibility from the container's writer thread to the
  destruction-time reader is established by the capture itself: the volatile `totalBytes` is written
  last in every mutation, so the reader's first `totalBytes` read publishes all preceding buffer writes.

The wrappers exist only when a body is logged (in any mode) **or** measured; without either, the chain
receives the original request and response. What flows beside the wrappers — the container's error
rendering, a raw `startAsync()` cycle — and what discards a capture is [§4.3](#43-body-rules).

### 2.7 MDC coverage

The module advertises "request identity in MDC while the request is handled". Concretely:

| Thread / phase | Mechanism | Covered |
|---|---|---|
| Initial dispatch (the filter chain, the controller, every filter after this one) | chain-wide `MdcScope` in `doFilterInternal` | yes |
| Spring MVC `Callable` / `WebAsyncTask` worker thread | `EndpointMdcCallableInterceptor` (`preProcess` / `postProcess` on that thread) | yes |
| The container's async **re-dispatch** (result/error rendering) | `MdcScope` in `filterAsyncDispatch` | yes |
| The emission at request destruction | `MdcScope` in the emitter, with trace ownership | yes |
| `DeferredResult` producers, raw `startAsync()` workers, `@Async` methods, custom executors | — application-owned threads neither the container nor Spring routes through this module | **no** — propagate context yourself (e.g. a `TaskDecorator` copying `MDC.getCopyOfContextMap()`) |

`MdcScope` is an **additive overlay**: it puts the three `endpoint_*` keys and restores the previous
values on close (container threads are pooled; an outer filter may own the same keys). Around the chain
it leaves the trace keys alone — a tracing bridge's own scope is authoritative there. Around the
emission it **owns** them (a bridge's `spanId` included): a parsed id is installed, an unparsed one is
removed for the scope's lifetime, so a stale id on the pooled destruction thread can never join the
event to a foreign trace.

### 2.8 Fail-open stages

The contract — no failure inside the logging may fail, delay or alter the request; every failure
counted by stage and reported on the module's own loggers; the security note on what fail-open means for
an audit trail — is the [common guide's §2.4](../../docs/GUIDE.md#24-fail-open-contract). The boundaries
on this stack:

| Stage | Where | What happens on failure | Counted as |
|---|---|---|---|
| wiring | `wireExchange` (correlation bean, header enumeration, capture construction) | the filter degrades to a plain pass-through for this request | `failopen{stage=wiring}` |
| wiring | `MdcScope` open (initial or async dispatch) | the chain runs without chain MDC | `failopen{stage=wiring}` |
| wiring | `MdcScope` close | restoration lost; never masks an application exception propagating out of the chain | `failopen{stage=wiring}` |
| wiring | async interceptor registration, worker `preProcess`/`postProcess` | worker logs lose the identity | `failopen{stage=wiring}` |
| wiring | post-chain bookkeeping (template read, async listener, breadcrumb) | confined; the event still follows at destruction | `failopen{stage=wiring}` |
| wiring | body-size recording, operational counter updates | the event follows without the sample / the count | `failopen{stage=wiring}` |
| arrival | `logRequestStart` (including the level gate) | the arrival line is dropped | `failopen{stage=arrival}` |
| emission | `logExchange` — everything after the exactly-once CAS | the exchange event is **lost** | `failopen{stage=emission}` |
| registration | `EndpointLoggingMetrics.registerOrFallback` | the conflicting meter lives in a private registry, warned once per name | — |

The module's own loggers are `eu.inqudium.limesium.servlet.logging.RequestLoggingFilter`,
`…ExchangeLogEmitter`, `…EndpointLoggingMetrics` and `…EndpointMdcCallableInterceptor`.

---

## 3. Using it in a foreign project

### 3.1 Prerequisites

The requirements shared by both modules — Spring Boot 4.x, Java 21 with the Kotlin stdlib, an SLF4J 2.x
binding, Micrometer core — are the [common guide's §3.1](../../docs/GUIDE.md#31-prerequisites); the
dependency snippet and the badge are its [§3.2](../../docs/GUIDE.md#32-adding-the-dependency). This
stack adds:

| Requirement | Notes |
|---|---|
| Spring Boot 4.x **servlet** web application (embedded Tomcat 11+ or Jetty 12.1+) | `@ConditionalOnWebApplication(type = SERVLET)`; the module is inert in a reactive application. Both containers are pinned by their own integration suites (capture boundaries + tracing) and documented per engine in [CONTAINERS.md](CONTAINERS.md). **Undertow — and therefore WildFly, whose servlet engine it is — is UNSUPPORTED on this stack:** Spring Framework 7's baseline is Jakarta Servlet 6.1 ("deploy on Tomcat 11+/Jetty 12.1+", with no runtime-compatibility statement downwards), Undertow (latest 2.3.x) implements only 6.0, and Spring Boot 4 removed the Undertow starter for that reason. It is a platform boundary, not a module limitation: the module's OWN servlet-API surface is Servlet 3.1-level (listener-based emission: 2.4; async lifecycle: 3.0; non-blocking-I/O tee hooks: 3.1) with one dormant 6.1 piece (the `sendRedirect` overloads `CapturingResponseWrapper` overrides for the 6.1 buffer-clearing redirect variants), and a bytecode scan of the servlet-MVC path (spring-web/-webmvc/Boot servlet layer, 2026-08-30) found NO hard Servlet 6.1 invocation either — the only real ones in spring-web sit in the reactive-on-servlet adapter, which MVC never touches. An unsupported-territory suite (`RequestLoggingFilterUndertowIntegrationTest`, hand-rolled embedded-Undertow factory) pins that empirically: the module runs and the capture boundaries hold, with two pinned engine deviations (wrappers handed to zero-argument `startAsync()`, so the raw-async body IS captured; the default error rendering rebuilds the response and drops the correlation echo from error responses). No guarantee from Spring follows — the suite is the tripwire for a patch release adopting 6.1 API; the boundary lifts properly when Undertow ships Servlet 6.1 |
| Jakarta Servlet API on the runtime classpath | `provided` scope in the module; the host's container supplies it |
| Spring MVC | optional — `endpoint_url_template`, the async pass and the worker MDC need it; the module depends on `spring-web` only and degrades gracefully without MVC |

The module is a **library**, not a starter: it declares `spring-boot-autoconfigure`, `slf4j-api`,
`spring-web`, `micrometer-core`, `kotlin-stdlib` and the `provided` servlet API — no logging backend, no
YAML, no container are forced onto the host.

### 3.2 Automatic wiring

The shipped activation is not the filter bean but the two **registrations** the auto-configuration
places around it. `RequestLoggingAutoConfiguration` is listed in the auto-configuration imports resource
and is conditional on two things: a **servlet** web application (`@ConditionalOnWebApplication(type =
SERVLET)` — in a reactive application the module is inert, and the reactive twin takes over) and
`endpoint-logging.enabled` (default `true`; `false` removes filter, registrations and defaults
together). Both are pinned by `RequestLoggingAutoConfigurationTest`.

| Registration | What it does | Why it is needed |
|---|---|---|
| `FilterRegistrationBean<RequestLoggingFilter>` | puts the filter into the container's chain at `Ordered.HIGHEST_PRECEDENCE + 10`, mapped to `/*`; Boot registers a `OncePerRequestFilter` for **every dispatcher type**, so the async and error dispatches pass it too (the filter handles the async dispatch itself — `shouldNotFilterAsyncDispatch` is `false` — and a request it never saw is ignored downstream) | the filter must see the request before anything logs and before the response can be committed ([§3.4](#34-filter-order-and-other-filters)) |
| `ServletListenerRegistrationBean<ServletRequestListener>` | registers `filter.exchangeCompletionListener()` on the servlet context | the **emission point**: the container fires `requestDestroyed` after the error dispatch and after async completion, and only then is the status final ([§2.4](#24-emission-point-request-destruction)) |

Both registrations go through Boot's `ServletContextInitializer` mechanism, which Boot executes on an
embedded container and — through its WAR support — on an external Tomcat or Jetty alike
([CONTAINERS.md §6](CONTAINERS.md#6-deployment-notes)). Referencing the filter **bean** from the
registration keeps Boot from also auto-registering the bare `Filter` bean it would otherwise pick up
on its own, at its own default order and without the listener.

Consequently there is nothing for the host to inject and nothing to build: every request the container
dispatches passes the filter, and **path activation** (`include-path-patterns`,
`exclude-path-prefixes`) is evaluated inside the filter's `shouldNotFilter`, not through the
registration's URL mapping — so its semantics are byte-identical with the reactive twin
([§4.4](#44-path-activation)). Everything ordered after `+ 10` — Spring Security, the application's own
filters, the `DispatcherServlet` and the handler — runs inside the chain-wide MDC scope and sees
`endpoint_request_id`, `endpoint_method` and `endpoint_route` on its own log lines:

```kotlin
@RestController
class ThingsController(private val things: Things) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/api/things/{id}")
    fun thing(@PathVariable id: Long): Thing {
        log.info("loading thing")        // carries endpoint_request_id, endpoint_method, endpoint_route
        return things.load(id)
    }
}
```

Covered by the automatic wiring:

- every request the container dispatches to the application, whatever handler ends it — a controller,
  a router function, a static resource, an error page;
- the async paths — `DeferredResult`, `Callable`, `suspend` controllers — through the async dispatch
  and the completion listener ([§2.5](#25-async-exchanges));
- a host-defined `RequestLoggingFilter` bean: the auto-configured registration and listener wrap the
  **host's** filter instead of creating a second one ([§3.5](#35-replacing-the-filter-bean); pinned by the
  auto-configuration test).

**Not** covered — for these, [§3.3](#33-manual-wiring) applies:

- a servlet application whose container is assembled without Boot's servlet auto-configuration;
- a container outside a Spring context.

The wiring itself is fail-open like everything else: a failure while wiring one exchange degrades that
request to a pass-through with a `stage=wiring` count ([§2.8](#28-fail-open-stages)); the
registrations cannot fail in a way that breaks the container.

To confirm the attachment at runtime — in a test or a startup check — the registration bean carries the
order, and the first request tells the rest:

```kotlin
val registration = context.getBean(FilterRegistrationBean::class.java)
check(registration.filter is RequestLoggingFilter && registration.order == Ordered.HIGHEST_PRECEDENCE + 10)
```

```bash
curl -i -H 'X-Correlation-Id: demo-1' http://localhost:8080/api/things/42   # echo on the response, one endpoint-http-exchange line
```

### 3.3 Manual wiring

The filter bean `RequestLoggingFilter` exists in every enabled servlet context; only its
**registration** — the filter in the chain and the completion listener on the servlet context —
depends on Boot's servlet auto-configuration. Register both yourself when that auto-configuration is
not in charge:

| Situation | Why the automatic wiring does not reach it |
|---|---|
| Spring MVC bootstrapped **without Boot**, or with Boot's servlet auto-configuration excluded | no `ServletContextInitializer` runs, so neither `FilterRegistrationBean` nor `ServletListenerRegistrationBean` reaches the container; a bare `RequestLoggingFilter` bean would be picked up by Boot as a plain `Filter` — at Boot's default order and **without the listener**, so nothing is ever emitted |
| A different order or URL mapping | the auto-configured registration is fixed at `HIGHEST_PRECEDENCE + 10` for `/*`; the host that must place the filter elsewhere switches the auto-configuration off (`endpoint-logging.enabled=false`) and registers by hand — mindful that [§6.8](#68-the--10-order-is-load-bearing) explains why the `+ 10` is load-bearing |
| A container outside a Spring context | a bare embedded Tomcat or Jetty in an integration test, a servlet application without Spring: there is no context to hold the bean, so the filter is constructed directly (below) |

The mechanics are two registrations on the same `ServletContext`: the filter for **every dispatcher
type** (as Boot does for a `OncePerRequestFilter`, so the async and error dispatches pass it too),
mapped to `/*` and ordered before the application's own filters, and the completion listener from
`exchangeCompletionListener()`. Without the listener no exchange is ever emitted, and the
`endpoint.logging.exchanges.open` gauge grows with every request — the liveness signal doing its job:

```kotlin
class EndpointLoggingInitializer : WebApplicationInitializer {
    override fun onStartup(servletContext: ServletContext) {
        val filter = RequestLoggingFilter(
            RequestLoggingProperties(),            // every default; or a copy(...) with the fields to change
            NanoTimeSource.SYSTEM,
            CorrelationIdGenerator.DEFAULT,
            SimpleMeterRegistry(),                 // or the registry the surrounding code owns
        )
        servletContext.addFilter("requestLoggingFilter", filter)
            .addMappingForUrlPatterns(EnumSet.allOf(DispatcherType::class.java), false, "/*")
        servletContext.addListener(filter.exchangeCompletionListener())
    }
}
```

Inside a Boot context with the auto-configuration switched off, the same two registrations are beans —
and the properties class must be bound by the host, because `@EnableConfigurationProperties` lives on
the auto-configuration that is now gone:

```kotlin
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RequestLoggingProperties::class)
class EndpointLoggingConfiguration {
    @Bean
    fun requestLoggingFilter(properties: RequestLoggingProperties, registry: MeterRegistry): RequestLoggingFilter =
        RequestLoggingFilter(properties, NanoTimeSource.SYSTEM, CorrelationIdGenerator.DEFAULT, registry)

    @Bean
    fun requestLoggingFilterRegistration(filter: RequestLoggingFilter): FilterRegistrationBean<RequestLoggingFilter> =
        FilterRegistrationBean(filter).apply { order = Ordered.HIGHEST_PRECEDENCE + 10 }

    @Bean
    fun requestLoggingExchangeCompletionListener(filter: RequestLoggingFilter): ServletListenerRegistrationBean<ServletRequestListener> =
        ServletListenerRegistrationBean(filter.exchangeCompletionListener())
}
```

The rules that hold for a hand-wired filter on either stack — one filter per `MeterRegistry`,
activation is not the host's business, the overridable beans stay overridable, the host binds the
properties class — are the [common guide's §3.3](../../docs/GUIDE.md#33-wiring). Two are servlet-specific:

- **Register both pieces.** The filter without the listener wires every exchange and completes none;
  the listener without the filter finds no exchange attribute and is a no-op.
- **Ordering is the host's business.** The automatic wiring guarantees the early position; a manual
  registration lands where the host puts it. Keep it early — inside Boot's observation filter, before
  everything that logs — and read [§6.8](#68-the--10-order-is-load-bearing) before moving it.

Everything else is unchanged by the way the filter was registered: emission point, outcomes, meters,
the chain-wide MDC, header sections, body capture and the fail-open contract behave exactly as under
the automatic wiring — the filter does not know how it got into the chain.

### 3.4 Filter order and other filters

The filter is registered at `Ordered.HIGHEST_PRECEDENCE + 10`. The chain runs in ascending order, so:

- Boot's `ServerHttpObservationFilter` (`HIGHEST_PRECEDENCE + 1`) wraps this filter — the trace context
  itself comes from the `traceparent` header, not from that filter's MDC, but running inside the
  observation keeps the exchange event within the server span's timing
  ([§6.8](#68-the--10-order-is-load-bearing));
- everything ordered after `+ 10` — Spring Security, the application's own filters, the
  `DispatcherServlet` — runs **inside** the chain-wide MDC scope and sees `endpoint_request_id`;
- on a traceless exchange the correlation id is echoed on the response before any later filter can
  commit it (a traced exchange writes no header at all — ADR-0002).

Path activation is evaluated **in the filter** (`shouldNotFilter`), not via the registration's URL
patterns, so its semantics are byte-identical with the reactive twin. A host that needs a different
order registers by hand ([§3.3](#33-manual-wiring)).

### 3.5 Replacing the filter bean

The collaborator beans — `CorrelationIdGenerator`, `HeaderValueMasker`, `NanoTimeSource` — are
overridden the same way on both stacks
([common guide §3.4](../../docs/GUIDE.md#34-overriding-the-collaborator-beans)). The filter itself is
also `@ConditionalOnMissingBean`, and replacing it is where this stack differs: a host-defined
`RequestLoggingFilter` bean replaces the **filter**, not the wiring. The auto-configured
`FilterRegistrationBean` (order) and the `ServletListenerRegistrationBean` (the emission point) are still
registered around it, so the emission at request destruction stays intact. The constructor takes
`(RequestLoggingProperties, NanoTimeSource, CorrelationIdGenerator, MeterRegistry)` plus an optional
trailing `HeaderValueMasker` (the built-in fingerprint when omitted):

```kotlin
@Bean
fun requestLoggingFilter(
    properties: RequestLoggingProperties,
    nanoTime: NanoTimeSource,
    ids: CorrelationIdGenerator,
    registry: MeterRegistry,
): RequestLoggingFilter = RequestLoggingFilter(properties, nanoTime, ids, registry)
```

Keep in mind the one-instance-per-registry limitation of the gauge
([common guide §6.2](../../docs/GUIDE.md#62-one-metrics-instance-per-registry)).

---

## 4. Configuration on the servlet stack

The namespace, every property with its default, the header sections, the body modes, the logger levels,
the startup validation and the example configurations are the
[common guide's §4](../../docs/GUIDE.md#4-configuration) — identical on both stacks by construction; the
reference file is [`/docs/endpoint-logging-reference.yml`](../../docs/endpoint-logging-reference.yml),
bound by this module's `EndpointLoggingReferenceConfigTest`. The reactive twin's one extra key,
`variant`, does not exist here. This section lists what the servlet stack adds to the meaning of
individual properties.

### 4.1 Property notes

| Property | On this stack |
|---|---|
| `enabled` | `false` removes the filter, both registrations, the listener and the default beans together. |
| `correlation-id-header` | The traceless echo is set once at filter entry. Downstream code that sets the header itself or calls `response.reset()` decides what the client finally sees; event and MDC keep the id resolved at entry. |
| `log-request-start` | The arrival line is logged inside the chain MDC scope, before the chain runs. |
| `include-path-patterns` / `exclude-path-prefixes` | Matched against the request URI within the application — the context path stripped, segments decoded ([§4.4](#44-path-activation)). An excluded request gets no chain MDC either. |
| `slow-request-threshold` | Compared against **request occupancy** — filter entry to request destruction, error rendering and async waiting included ([§6.1](#61-duration-is-request-occupancy)). |

### 4.2 Header selection

Multi-valued headers are resolved through `getHeaders(name)` and joined with `, ` — a single-value
`getHeader` would silently truncate repeated headers (`Set-Cookie` being the classic); the `*` selection
deduplicates names case-insensitively, because servlet enumerations may repeat them.

Request headers are selected at **filter entry**; response headers at **request destruction**, so they
reflect what the chain and the container's error rendering set.

### 4.3 Body rules

In addition to the rules that hold on both stacks
([common guide §4.3](../../docs/GUIDE.md#43-body-logging-and-body-measuring)):

- Streaming **and async** behaviour are untouched by the tee; a raw zero-argument `startAsync()` cycle
  flows beside it, though ([§6.4](#64-raw-startasync-bypasses-the-tee)).
- The log charset is the declared request/response encoding, UTF-8 when absent or unparsable; on the
  request side it is bound **late**, when the application first selects the stream or the reader, and
  the reader itself keeps the servlet decoding contract
  ([§6.6](#66-request-charset-log-rendering-vs-the-servlet-contract)).
- The capture follows the response buffer: `reset()`, `resetBuffer()`, `sendError` and buffer-clearing
  redirects discard it ([§6.3](#63-buffer-clearing-operations-discard-the-capture)).
- The container's own error rendering (Boot's error page after `sendError` or an unhandled exception)
  is written through the original response and bypasses the tee
  ([§6.2](#62-container-error-rendering-bypasses-the-response-tee)).
- The writer tee encodes through one stateful encoder, so split surrogate pairs across write chunks are
  captured faithfully ([§6.7](#67-writer-fidelity-and-checkerror)).

### 4.4 Path activation

The activation rule and the pattern syntax are the [common guide's §4.4](../../docs/GUIDE.md#44-path-activation).
On this stack both lists see the request target the way **Spring MVC** routes it — the **path within the
application** (a configured `server.servlet.context-path` is stripped first, exactly as in MVC's handler
mapping), parsed into segments that **decode for matching** and drop path parameters — so `/api/**`
matches `/app/api/things` under context path `/app`, `/%61pi/things` is included by `/api/**` and
`/%61ctuator/health` is excluded by `/actuator/health`, exactly as the router would serve them. The
logged `endpoint_url_path` stays the raw request URI and keeps the context path. An inactive request
passes through without any trace — no correlation echo, no chain MDC, no event, no gauge movement, no
counters.

### 4.5 Logger levels

The level/outcome decoupling and the cost model of a disabled level are the
[common guide's §4.5](../../docs/GUIDE.md#45-logger-levels). With this stack's dispositions:

| `endpoint-http-exchange` level | Emitted |
|---|---|
| `INFO` | every exchange |
| `WARN` | failures (5xx), container async timeouts, slow exchanges — and thrown chains |
| `ERROR` | only exchanges whose chain threw or whose async cycle errored |
| `OFF` | nothing — and no event is even assembled |

---

## 5. Metrics and observation on the servlet stack

The field family with its index types, the MDC keys, the six meters, how to read them together with the
suggested alert set, and the trace contract are the
[common guide's §5](../../docs/GUIDE.md#5-metrics-and-observation). This section lists what the servlet
stack decides within them.

### 5.1 Log fields

| Field | On this stack |
|---|---|
| `endpoint_outcome` | `success` / `failure` / **`timeout`** — the container's async timeout ([§5.3](#53-levels-and-outcomes)). |
| `endpoint_duration_ms` | Request occupancy until destruction ([§6.1](#61-duration-is-request-occupancy)). |
| `endpoint_response_status_code` | Always present — the final status at destruction, after the error dispatch. |
| `endpoint_async` | Emitted on every event: `true` when the chain returned with async processing started ([§2.5](#25-async-exchanges), [§6.5](#65-async-started-and-completed-inside-the-chain)). |
| `endpoint_url_template` | Present when Spring MVC recorded a handler pattern under `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` (mirrored as a constant so the module depends on `spring-web` only; pinned by `HandlerMappingAttributeTest`). |
| `endpoint_response_body` | Absent for container-rendered error responses ([§6.2](#62-container-error-rendering-bypasses-the-response-tee)). |

A wrongly typed value drops that field with a warning on
`eu.inqudium.limesium.servlet.logging.EndpointLogField`, never the event. The throwable of a failed chain —
or of an async `onError`/`onTimeout` event, when the container supplied one — is attached to the event as
its cause (`setCause`).

### 5.2 MDC keys

| Key | Scope on this stack |
|---|---|
| `endpoint_request_id`, `endpoint_method`, `endpoint_route` | the whole filter chain, the Spring MVC async worker, the async re-dispatch, and the emission ([§2.7](#27-mdc-coverage)) |
| `traceId`, `parentSpanId` | emission only — a tracing bridge owns the keys during the chain ([§5.6](#56-trace-correlation)) |

### 5.3 Levels and outcomes

The resolution order of the [common guide's §5.3](../../docs/GUIDE.md#53-levels-and-outcomes) — a thrown
chain first, a 5xx the application handled after the stack's own dispositions, `success` otherwise, slowness
raising severity — has two servlet-specific rows, resolved between the thrown chain and the 5xx:

| Condition | Level | `endpoint_outcome` | Cause attached |
|---|---|---|---|
| async disposition `TIMED_OUT` | `WARN` | `timeout` | the `onTimeout` throwable, if any |
| async disposition `ERRORED` | `ERROR` | `failure` | the `onError` throwable, if any |

"The chain threw" covers the initial **and** the async dispatch. `timeout` wins over a subsequent
`onError` (the container aborting the timed-out cycle) by construction of `AsyncDisposition`
([§2.5](#25-async-exchanges)).

### 5.4 Meters

| Meter | On this stack |
|---|---|
| `endpoint.logging.events` | the `outcome` tag carries `timeout` as the third value |
| `endpoint.logging.exchanges.open` | counts exchanges between filter entry and **request destruction** |
| `endpoint.logging.failopen{stage=wiring}` | includes a lost chain or worker MDC scope ([§2.8](#28-fail-open-stages)) |
| `endpoint.request.body.read{state=partial}` | consumption started but the end of the stream was never observed — an early-exiting parser, an exception mid-read, a read loop that never asked for the final EOF |

Registration conflicts are warned once per meter name on
`eu.inqudium.limesium.servlet.logging.EndpointLoggingMetrics`.

### 5.5 Reading the meters together

In the [common guide's table](../../docs/GUIDE.md#55-reading-the-meters-together), "the emission point
never fired" means on this stack: `requestDestroyed` is not firing for requests the filter saw — nothing
throws, so no fail-open count, and the `exchanges.open` baseline grows monotonically. The
[container guide](CONTAINERS.md) documents the destruction model of every supported engine, and the
per-dispatch model of Jetty in particular. `events{outcome="timeout"}` answers whether async cycles are
timing out.

### 5.6 Trace correlation

The `traceparent` parse, the keys and the strict conformance are the
[common guide's §5.6](../../docs/GUIDE.md#56-trace-correlation). On this stack the trace keys have two
owners at two moments: around the chain, the `MdcScope` leaves them alone — a tracing bridge's own
scope is authoritative there, and its `traceId` is the same one the header carried; around the emission,
the destruction callback's thread carries no per-request state, so the emission scope **owns** the keys
and restores the parsed pair (or removes a stale one, a bridge's `spanId` included) around the event.
`RequestLoggingFilterTomcatTracingIntegrationTest` pins the parsed context and the identity decision
against a real Brave bridge running beside it; the Jetty and Undertow tracing suites pin the trace-key
suppression against those engines' emission threads ([CONTAINERS.md](CONTAINERS.md)).

---

## 6. Special characteristics

The characteristics shared by both stacks — the one-instance-per-registry limitation of the gauge, the
masking fingerprint, the shared code inlined from `limesium-common` — and the complete list of
deliberate differences to the reactive twin are the
[common guide's §6](../../docs/GUIDE.md#6-shared-characteristics). What follows is servlet-only.

### 6.1 Duration is request occupancy

`endpoint_duration_ms` is measured from filter entry to **request destruction**: it includes the
container's error rendering and, for an async exchange, the whole time the request was parked waiting
for the result. It is not bare handler time and not what `http.server.requests` measures. The slow
threshold is compared against this occupancy.

### 6.2 Container error rendering bypasses the response tee

`OncePerRequestFilter` skips the **ERROR dispatch**, so the final body of a container error page (Boot's
error page after `sendError` or an unhandled exception) is written through the **original** response.
The event still carries the rendered **status** (read at destruction), but `endpoint_response_body` and
the response-size sample stay absent for container-rendered error responses. Responses rendered locally —
a controller's `ResponseEntity`, a `@ControllerAdvice` — traverse the tee normally. Pinned by integration
test.

### 6.3 Buffer-clearing operations discard the capture

A reset of an **uncommitted** response discards everything buffered — nothing written so far ever
reached the client — so the capture is discarded with it; otherwise the logged body and the size metric
would report bytes the client never saw (a partially written response an `@ExceptionHandler` throws away
and rewrites). This applies to `reset()`, `resetBuffer()`, both `sendError` variants and the
buffer-clearing `sendRedirect` variants, which clear the buffer per the servlet spec without going
through the reset overrides. On a **committed** response `super.reset()` throws per spec and the capture
stays intact. A full `reset()` also drops the cached tee stream/writer, mirroring the servlet rule that
the previously returned object is stale.

### 6.4 Raw `startAsync()` bypasses the tee

The tee lives on the wrappers the filter passes down the chain. Spring MVC's async support calls
`startAsync(currentRequest, currentResponse)` and keeps the wrappers. The servlet-specified
**zero-argument** `startAsync()` initialises its context with the **original** request/response, so
bytes a raw async cycle reads or writes flow beside the tee and are logged as absent. A documented
boundary, pinned by integration test.

### 6.5 Async started and completed inside the chain

The filter reads `request.isAsyncStarted` in its `finally`. An async cycle that starts **and** completes
within the chain reads `false` there: such an exchange logs `endpoint_async=false` and gets no
`AsyncOutcomeMarker`, so a timeout or error inside that window would go unmarked. The servlet API offers
no portable "was async ever started" signal; accepted and documented.

### 6.6 Request charset: log rendering vs. the servlet contract

`CapturingRequestWrapper` preserves the servlet decoding contract **exactly** for the application: the
reader uses the declared request encoding, the spec default ISO-8859-1 when none is declared, and throws
`UnsupportedEncodingException` for an unsupported one — as an unwrapped request would. The **log** charset
is separate: UTF-8 when no encoding is declared (modern payloads without a declaration are far more
likely UTF-8), and it is bound **late** — when the application first selects the stream or the reader —
because the servlet contract lets downstream code call `setCharacterEncoding` until the body is consumed.
A charset frozen at filter entry would decode the captured bytes with an encoding the application never
used.

### 6.7 Writer fidelity and `checkError()`

The response writer tee encodes through **one stateful encoder** with the writer's lifecycle, so a
surrogate half pending at the end of a write chunk stays in the encoder until its partner arrives
(chunk-local `String.toByteArray` emitted replacement bytes for every split sequence). Completed
characters land in the capture immediately — the emission must not wait for a writer close the
application may never call.

`checkError()` on the returned `PrintWriter` reflects the **delegate's** suppressed-error state: the
container's writer swallows `IOException`s into an internal flag, and an outer `PrintWriter` over the tee
would otherwise answer `false` after the real writer failed. Residual: a chunk the delegate swallowed an
`IOException` for (client disconnect mid-write) is still counted as flowed — `PrintWriter` suppresses the
failure before any tee can see it.

<!-- Explicit anchor: GitHub slugifies this heading with a double
     hyphen (the `+` is dropped), MkDocs with a single one; the anchor
     keeps the GitHub-style TOC links working on the docs site too. -->
<a id="68-the--10-order-is-load-bearing"></a>

### 6.8 The `+ 10` order is load-bearing

Boot registers `ServerHttpObservationFilter` at `Ordered.HIGHEST_PRECEDENCE + 1`, and the chain runs in
ascending order — so that filter wraps this one. Since ADR-0002 the trace context no longer depends on
that ordering (it is parsed from the `traceparent` header, not captured from the bridge's MDC), but the
order stays at `+ 10` deliberately: the exchange runs inside the server span's observation, the chain
MDC scope opens before Security and the application filters, and the traceless echo lands before any
later filter can commit the response. `RequestLoggingFilterTomcatTracingIntegrationTest` pins the parsed
context against a real bridge — its MDC writes and its own server span must never leak into the event.

---

## 7. Appendix

### 7.1 File map

```
limesium-servlet-logging/
├── pom.xml                                   library deps only; servlet API provided
├── README.md                                 module summary, twin decision
├── docs/
│   ├── activity-diagram.svg                  UML activity diagram of one exchange
│   ├── CONTAINERS.md                         the per-container guide (Tomcat, Jetty, Undertow)
│   └── GUIDE.md                              this document
└── src/
    ├── main/kotlin/eu/inqudium/limesium/servlet/logging/
    │   ├── RequestLoggingAutoConfiguration.kt     filter, registration, listener, defaults
    │   ├── RequestLoggingProperties.kt            endpoint-logging.* binding (HeaderLogProperties: common guide §6.4)
    │   ├── RequestLoggingFilter.kt                the servlet lifecycle, completion listener
    │   ├── Exchange.kt                            per-exchange state, AsyncDisposition, AsyncOutcomeMarker
    │   ├── EndpointMdcCallableInterceptor.kt      MDC on the MVC async worker
    │   ├── ExchangeLogEmitter.kt                  arrival line and completion event
    │   ├── EndpointLogFields.kt                   field enum and builder helpers (owns the family)
    │   ├── EndpointLoggingMetrics.kt              the six meters
    │   ├── CapturingRequestWrapper.kt             request stream/reader tee
    │   ├── CapturingResponseWrapper.kt            response stream/writer tee
    │   └── BoundedBodyCapture.kt                  bounded capture target, BodyReadState
    │   (Traceparent, Mdc, NanoTimeSource, CorrelationIdGenerator, HeaderValueMasker and
    │    reportQuietly live in ../limesium-common - inlined into this jar, common guide §6.4)
    ├── main/resources/META-INF/spring/…AutoConfiguration.imports
    └── test/kotlin/eu/inqudium/limesium/servlet/logging/   see the suite overview below
```

Test-suite overview (the generated [test-evidence page](https://inqudium.github.io/limesium/tests/test-evidence/)
lists every test with its rationale):

| Suite | Scope |
|---|---|
| Unit suites (`RequestLoggingFilterTest`, `…AsyncTest`, `…BodyAndHeaderTest`, `…FailOpenCounterTest`, `…TraceContextTest`, `ExchangeLogEmitterTest`, `BoundedBodyCaptureTest`, …) | mock-driven, deterministic; the async suite drives the per-dispatch destruction choreography by hand |
| `RequestLoggingFilterTomcatIntegrationTest` | capture boundaries on real embedded **Tomcat** (the reference container); owns the shared `ItApp` |
| `RequestLoggingFilterJettyIntegrationTest` | capture boundaries on real embedded **Jetty** — found and pins Jetty's per-dispatch destruction model |
| `RequestLoggingFilterUndertowIntegrationTest` | capture boundaries on real embedded **Undertow** (hand-rolled factory, unsupported territory — the tripwire from the container-support note), incl. the two pinned engine deviations |
| `RequestLoggingFilterTomcatTracingIntegrationTest` | ADR-0002 trace contract beside a real Brave bridge on Tomcat, plus the container-independent bridge-propagation assertions |
| `RequestLoggingFilterJettyTracingIntegrationTest` | trace-key suppression against Jetty's LIVE in-dispatch bridge MDC |
| `RequestLoggingFilterUndertowTracingIntegrationTest` | trace-key suppression on Undertow's emission threads |
| Lockstep/contract tests (`TwinContractTest`, `EndpointLogFieldTest`, `EndpointLoggingReferenceConfigTest`, `HandlerMappingAttributeTest`) | pin the twin/wire/config contracts |
| Fuzz targets (`src/test/java`: `BoundedBodyCaptureFuzzTest`; `Traceparent` and header-masking fuzzing lives in limesium-common) | Jazzer `@FuzzTest`, corpus replay in every build, nightly exploration |

### 7.2 Related documents

- [Common guide](../../docs/GUIDE.md) — everything shared by both modules: the exchange line, the shared
  architecture, dependency and encoder setup, the configuration namespace, the field family, the
  meters, the trace contract, and the [table of stack differences](../../docs/GUIDE.md#61-differences-between-the-stacks).
- [`CONTAINERS.md`](CONTAINERS.md) — the per-container guide: Tomcat, Jetty and Undertow documented
  individually (destruction models, error paths, pinned deviations, suites).
- [`README.md`](../README.md) — module summary and the twin decision.
- [`/docs/endpoint-logging-reference.yml`](../../docs/endpoint-logging-reference.yml) — the complete commented
  configuration reference; every key and default, bound by `EndpointLoggingReferenceConfigTest` here and
  in the twin.
- [`/docs/elk/README.md`](../../docs/elk/README.md) — the Elasticsearch component template for the `endpoint_*`
  fields and the access pattern behind each mapping decision.
- [`limesium-reactive-logging/docs/GUIDE.md`](../../limesium-reactive-logging/docs/GUIDE.md) — the
  twin's guide: what the reactive stack decides.
