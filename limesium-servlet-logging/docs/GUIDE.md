# limesium-servlet-logging — Guide

One structured `endpoint_*` log line per HTTP exchange in a Spring Boot **servlet** (Tomcat) application,
with the exchange identity in the MDC while the request is handled. This module is the **reference
implementation** of the endpoint-logging family; its WebFlux twin
[`limesium-reactive-logging`](../../limesium-reactive-logging/README.md) shares the message format, the
field family, the `endpoint-logging.*` configuration and the meters.

This guide is the long-form companion to the module [README](../README.md). It explains what the module
does, how it is built, how to drop it into a foreign application, what can be configured, what it
measures, and which behaviours are specific to the servlet stack. Everything here is derived from the
code under `src/main/kotlin/eu/inqudium/limesium/servlet/logging/`; when the two disagree, the code wins.

## Table of contents

1. [Introduction](#1-introduction)
   1. [What the module does](#11-what-the-module-does)
   2. [What the module deliberately does not do](#12-what-the-module-deliberately-does-not-do)
   3. [The exchange line](#13-the-exchange-line)
   4. [The reactive twin](#14-the-reactive-twin)
2. [Architecture](#2-architecture)
   1. [Component overview](#21-component-overview)
   2. [Auto-configuration and registration](#22-auto-configuration-and-registration)
   3. [Lifecycle of one exchange](#23-lifecycle-of-one-exchange)
   4. [Emission point: request destruction](#24-emission-point-request-destruction)
   5. [Async exchanges](#25-async-exchanges)
   6. [The body tee](#26-the-body-tee)
   7. [MDC coverage](#27-mdc-coverage)
   8. [Fail-open contract](#28-fail-open-contract)
   9. [Injectable collaborators](#29-injectable-collaborators)
3. [Using it in a foreign project](#3-using-it-in-a-foreign-project)
   1. [Prerequisites](#31-prerequisites)
   2. [Adding the dependency](#32-adding-the-dependency)
   3. [Filter order and other filters](#33-filter-order-and-other-filters)
   4. [Overriding beans](#34-overriding-beans)
   5. [Logging backend and structured output](#35-logging-backend-and-structured-output)
   6. [Index mapping (ELK)](#36-index-mapping-elk)
   7. [Verifying the integration](#37-verifying-the-integration)
4. [Configuration](#4-configuration)
   1. [Property reference](#41-property-reference)
   2. [Header sections](#42-header-sections)
   3. [Body logging and body measuring](#43-body-logging-and-body-measuring)
   4. [Path activation](#44-path-activation)
   5. [Logger levels](#45-logger-levels)
   6. [Validation at startup](#46-validation-at-startup)
   7. [Example configurations](#47-example-configurations)
5. [Metrics and observation](#5-metrics-and-observation)
   1. [Log fields](#51-log-fields)
   2. [MDC keys](#52-mdc-keys)
   3. [Levels and outcomes](#53-levels-and-outcomes)
   4. [Meters](#54-meters)
   5. [Reading the meters together](#55-reading-the-meters-together)
   6. [Trace correlation](#56-trace-correlation)
6. [Special characteristics](#6-special-characteristics)
   1. [Differences to the reactive twin](#61-differences-to-the-reactive-twin)
   2. [Duration is request occupancy](#62-duration-is-request-occupancy)
   3. [Container error rendering bypasses the response tee](#63-container-error-rendering-bypasses-the-response-tee)
   4. [Buffer-clearing operations discard the capture](#64-buffer-clearing-operations-discard-the-capture)
   5. [Raw `startAsync()` bypasses the tee](#65-raw-startasync-bypasses-the-tee)
   6. [Async started and completed inside the chain](#66-async-started-and-completed-inside-the-chain)
   7. [Request charset: log rendering vs. the servlet contract](#67-request-charset-log-rendering-vs-the-servlet-contract)
   8. [Writer fidelity and `checkError()`](#68-writer-fidelity-and-checkerror)
   9. [The `+ 10` order is load-bearing](#69-the--10-order-is-load-bearing)
   10. [One metrics instance per registry](#610-one-metrics-instance-per-registry)
   11. [Masking is a fingerprint, not a secret](#611-masking-is-a-fingerprint-not-a-secret)
   12. [Shared code: limesium-common, inlined by Shade](#612-shared-code-limesium-common-inlined-by-shade)
7. [Appendix](#7-appendix)
   1. [File map](#71-file-map)
   2. [Related documents](#72-related-documents)

---

## 1. Introduction

### 1.1 What the module does

`limesium-servlet-logging` is a Spring Boot auto-configured `OncePerRequestFilter` plus a
`ServletRequestListener` for **servlet** web applications. For every inbound HTTP exchange it:

- resolves the exchange identity per ADR-0002: a conformant `traceparent`'s trace id **is** the request
  id; only a traceless exchange adopts a correlation id from the configured request header (or
  generates one) and echoes it back on the response — a traced exchange passes through
  observationally untouched;
- puts `endpoint_request_id`, `endpoint_method` and `endpoint_route` into the **MDC for the whole filter
  chain** — and onto the Spring MVC async worker thread — so every application log line downstream is
  correlatable;
- optionally logs an **arrival line** the moment the request comes in;
- measures the exchange duration with an injectable monotonic time source;
- optionally tees the request and response bodies as they flow (bounded, never buffered or replayed);
- optionally records the selected request/response headers, with stable masking of sensitive values;
- parses the W3C `traceparent` header at filter entry (`traceId`/`parentSpanId`) so the event stays
  joinable with its trace;
- emits **exactly one** structured completion event at **request destruction** — after the container's
  error dispatch and after async completion, so the logged status is the one the client received;
- feeds six Micrometer meters that observe the logging itself.

It does all of this **fail-open**: no failure inside the logging — wiring, body tee, MDC adapter,
emission, metrics — can ever fail, delay or alter the request it describes.

### 1.2 What the module deliberately does not do

- **No request rates, latencies or status distributions as metrics.** Boot's `http.server.requests` and
  the structured log fields cover those; the module's meters observe only what those cannot show
  ([§5.4](#54-meters)).
- **No body masking transformers and no per-key response sampling.** Both were considered
  and dropped on purpose; bodies are logged verbatim up to the capture limit, and the logger level
  is the only volume control ([§4.5](#45-logger-levels)).
- **No replaying body cache.** The tee is passive; an unread request body is logged as absent.
- **No exporting of a `MeterRegistry`.** The host's registry is consumed if present; otherwise a private
  `SimpleMeterRegistry` absorbs the values.
- **No MDC on application-owned threads.** `DeferredResult` producers and raw servlet async workers run
  on threads neither the container nor Spring routes through this module ([§2.7](#27-mdc-coverage)).

### 1.3 The exchange line

On the logger `http-exchange` (configurable) a completed exchange looks like this in a plain-text
appender:

```
Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 parentSpanId=00f067aa0ba902b7]
```

The trace suffix appears only when the request carried a conformant W3C `traceparent` header — its
trace id then doubles as the request id (ADR-0002). Alongside the message, the event carries SLF4J key-values that a structured encoder
turns into fields:

```json
{
  "message": "Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 parentSpanId=00f067aa0ba902b7]",
  "level": "INFO",
  "logger": "http-exchange",
  "endpoint_outcome": "success",
  "endpoint_duration_ms": 17,
  "endpoint_request_method": "GET",
  "endpoint_response_status_code": 200,
  "endpoint_url_path": "/api/things/42",
  "endpoint_url_template": "/api/things/{id}",
  "endpoint_async": false,
  "endpoint_request_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "endpoint_method": "GET",
  "endpoint_route": "/api/things/42",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "parentSpanId": "00f067aa0ba902b7"
}
```

The `endpoint_request_id` / `endpoint_method` / `endpoint_route` / `traceId` / `parentSpanId` entries come from
the MDC ([§5.2](#52-mdc-keys)); the `endpoint_*` key-values are the field family of [§5.1](#51-log-fields).
How MDC entries land in the document (flat, nested, renamed) is the encoder's decision.

With the optional arrival line enabled, a second, earlier line precedes it:

```
Endpoint http exchange started GET /api/things/42 [endpoint_request_id=0f7c1a2e-...]
```

The arrival line carries no outcome, status or duration, so a dashboard keyed on `endpoint_outcome`
still sees exactly one event per exchange.

### 1.4 The reactive twin

The module is the **reference implementation** for the reactive twin. It owns the cross-stack
contract files, and the twin's build binds them:

| Contract | Shipped here | Pinned in the twin by |
|---|---|---|
| Configuration keys and defaults | [`/docs/endpoint-logging-reference.yml`](../../docs/endpoint-logging-reference.yml) | `EndpointLoggingReferenceConfigTest` (binds this YAML against the twin's properties class) |
| Field family and index mapping | [`/docs/elk/…component-template.json`](../../docs/elk/README.md) | `EndpointLogFieldTest` (locks the twin's enum against the template) |
| Message text and meter names | this module's emitter and metrics | `TwinContractTest` in both modules |

The consequence for a consumer: a dashboard, alert or index mapping written for one stack works
unchanged for the other.

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

| Class | Responsibility |
|---|---|
| `RequestLoggingAutoConfiguration` | Registers the filter bean, its `FilterRegistrationBean` (order `HIGHEST_PRECEDENCE + 10`), the `ServletListenerRegistrationBean` for the completion listener, and the default `NanoTimeSource` / `CorrelationIdGenerator`. |
| `RequestLoggingProperties` | The `endpoint-logging.*` binding, validated in `init`. `HeaderLogProperties` is one header section with `includes` / `excludes` / `masked` and the masking fingerprint. |
| `RequestLoggingFilter` | Owns the **servlet side**: path activation, fail-open wiring, identity resolution (`traceparent` first, correlation header on traceless exchanges) with the traceless echo, the tee wrappers, the chain-wide `MdcScope`, the async dispatch pass, the breadcrumb, the handoff to destruction. |
| `Exchange` / `AsyncDisposition` / `AsyncOutcomeMarker` | Per-exchange state from entry to emission; the async disposition as one atomic value with built-in precedence; the `AsyncListener` that marks timeout/error. |
| `EndpointMdcCallableInterceptor` | Restores the `endpoint_*` MDC on the Spring MVC `Callable`/`WebAsyncTask` worker thread. |
| `ExchangeLogEmitter` | Builds and emits the arrival line and the completion event; resolves level, outcome and cause; records body sizes; opens the emission `MdcScope` with trace ownership. |
| `EndpointLogField` | The wire names and the exact JVM type of each structured field; a wrongly typed value drops the field with a warning, never the event. |
| `EndpointLoggingMetrics` | The six meters - the fixed-tag meters pre-registered, the body meters created lazily per tag - with per-meter fallback to a private registry on registration conflict. |
| `CapturingRequestWrapper` / `CapturingResponseWrapper` | The servlet stream/reader and stream/writer tees. |
| `BoundedBodyCapture` | The bounded capture target; count-only mode with limit `0`; the request-side read state (`BodyReadState`); single-writer/late-reader visibility via a volatile total. |
| `MdcScope` | Puts identity (and, for the emission, trace keys) into the MDC and restores the previous values on close. |
| `NanoTimeSource` / `CorrelationIdGenerator` | Injectable time and id; `SYSTEM` and `DEFAULT` are the production defaults. |
| `reportQuietly` | Guards the diagnostics channel (counter + internal log) of every catch block. |

### 2.2 Auto-configuration and registration

`RequestLoggingAutoConfiguration` is listed in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and is conditional on
`@ConditionalOnWebApplication(type = SERVLET)` and `endpoint-logging.enabled` (default `true`). It
registers:

| Bean | Condition | Purpose |
|---|---|---|
| `NanoTimeSource` | `@ConditionalOnMissingBean` | `NanoTimeSource.SYSTEM` |
| `CorrelationIdGenerator` | `@ConditionalOnMissingBean` | `CorrelationIdGenerator.DEFAULT` (counting generator: random per-instance base-36 prefix + counter, 21 chars) |
| `RequestLoggingFilter` | `@ConditionalOnMissingBean` | the filter, built from the bound properties and the host's `MeterRegistry` (`ObjectProvider`; private `SimpleMeterRegistry` without one) |
| `FilterRegistrationBean<RequestLoggingFilter>` | always | order `Ordered.HIGHEST_PRECEDENCE + 10`; referencing the filter bean keeps Boot from also auto-registering the bare `Filter` |
| `ServletListenerRegistrationBean<ServletRequestListener>` | always | `filter.exchangeCompletionListener()` — the emission point |

Because the filter is its own bean, a host can replace it while keeping the registration and the
listener ([§3.4](#34-overriding-beans)). The servlet API is a `provided` dependency; the host's Tomcat
supplies it.

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
would log `-> 200` although the client received the 500 the container rendered afterwards. The event is
therefore emitted from `ServletRequestListener.requestDestroyed` — the moment the request finally goes
out of scope:

- after the service method returned,
- after the container's **ERROR dispatch** (Boot's error page, `sendError` handling),
- for an async exchange, after **async completion**.

So the logged status, response headers and captures are final and race-free. Two consequences:

1. `endpoint_duration_ms` measures **request occupancy** including error rendering and async waiting,
   not bare chain time ([§6.2](#62-duration-is-request-occupancy)).
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
all behind one exactly-once guard. The Jetty integration test pins this choreography per container.
The emitter reads `asyncStarted` (→ `endpoint_async: true`) and the disposition
([§5.3](#53-levels-and-outcomes)).

### 2.6 The body tee

Bodies are never pre-read, buffered or replayed. The module installs a **passive tee**:

- `CapturingRequestWrapper` overrides `getInputStream()` **and** `getReader()` (the base class would
  otherwise hand out the original request's reader and bypass the tee). Both are served from one
  delegate stream, so the wrapper reproduces the servlet either-or contract itself. Bytes are copied as
  the application reads them; an unread body is logged as absent.
- `CapturingResponseWrapper` overrides `getOutputStream()` and `getWriter()`. Every byte is forwarded to
  the real response **first** and copied second, so commit semantics, streaming and content length are
  those of an unwrapped response; nothing is withheld, so — unlike `ContentCachingResponseWrapper` —
  nothing has to be copied back, which is what makes it safe for async completion.
- `BoundedBodyCapture` is the target: a `ByteArrayOutputStream` of at most `max-body-bytes` and a total
  byte counter. With limit `0` it runs in **count-only** mode for the body-size meters. Visibility from
  the container's writer thread to the destruction-time reader is established by the capture itself: the
  volatile `totalBytes` is written last in every mutation, so the reader's first `totalBytes` read
  publishes all preceding buffer writes.

The wrappers exist only when a body is logged **or** measured; without either, the chain receives the
original request and response.

**The capture mirrors consumption, not transmission.** The filter sees exactly the bytes the application
actually reads or writes — no more. A request body the handler never consumes (a `@PostMapping` without
`@RequestBody`, a request rejected before the controller, an early abort) is logged as absent and records
no size sample, even though the client sent one; a body read only partially is captured to exactly that
extent, and the `[truncated, N bytes total]` note counts what flowed, not `Content-Length`. The same holds
on the response side: what the application writes through the tee is what the log shows. This is the
deliberate trade-off against a replaying buffer — the log tells the truth about what the
application processed, and streaming stays untouched. Because of that, the log cannot tell a body the client
sent but the application ignored from one that was never sent; the counter `endpoint.request.body.read`
([§5.4](#54-meters)) exists for exactly that distinction.

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

### 2.8 Fail-open contract

A logging component must never fail the request it describes. The module enforces that at every
boundary where it calls host-provided code (MDC adapter, appenders, `MeterRegistry`, the container's
request/response):

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

Every catch block reports through `reportQuietly`, which swallows a failure of the diagnostics channel
itself (a throwing `Counter`, a throwing appender that also covers the internal logger).
`InterruptedException` is caught separately and the interrupt flag is restored.

Failures of the logging are reported on the module's **own** loggers
(`eu.inqudium.limesium.servlet.logging.RequestLoggingFilter`, `…ExchangeLogEmitter`, `…EndpointLoggingMetrics`,
`…EndpointMdcCallableInterceptor`), never on the exchange logger, so the exchange stream stays parseable.

**Security note.** Fail-open is the inverse of what an audit log needs: a host-side fault silently
removes the exchange from the log instead of failing the request. The exchange log is therefore an
**observability** feature with no completeness guarantee; a regulatory audit trail must come from a
fail-closed component. The compensating controls are `endpoint.logging.failopen` and the
`exchanges.open` gauge ([§5.5](#55-reading-the-meters-together)) — alert on them.

### 2.9 Injectable collaborators

Time and randomness are injected, not ambient:

- `NanoTimeSource` — monotonic nanoseconds for `endpoint_duration_ms` and the slow threshold; the single
  production read of `System.nanoTime()` is `NanoTimeSource.SYSTEM`. Log timestamps come from the
  logging backend, keeping the two time domains separate.
- `CorrelationIdGenerator` — the id for traceless requests without a correlation header; `DEFAULT`
  by default. Never consulted for a traced exchange (ADR-0002: the `traceparent` trace id is the
  request id).

Both are `fun interface`s, both are `@ConditionalOnMissingBean` beans, and both are what the module's
tests drive from an `AtomicLong` / a fixed string without any mocking library.

---

## 3. Using it in a foreign project

### 3.1 Prerequisites

| Requirement | Notes |
|---|---|
| Spring Boot 4.x servlet web application (embedded Tomcat 11+ or Jetty 12.1+) | `@ConditionalOnWebApplication(type = SERVLET)`; the module is inert in a reactive application. Both containers are pinned by their own integration suites (capture boundaries + tracing). **Undertow — and therefore WildFly, whose servlet engine it is — is UNSUPPORTED on this stack:** Spring Framework 7's baseline is Jakarta Servlet 6.1 ("deploy on Tomcat 11+/Jetty 12.1+", with no runtime-compatibility statement downwards), Undertow (latest 2.3.x) implements only 6.0, and Spring Boot 4 removed the Undertow starter for that reason. It is a platform boundary, not a module limitation: the module's OWN servlet-API surface is Servlet 3.1-level (listener-based emission: 2.4; async lifecycle: 3.0; non-blocking-I/O tee hooks: 3.1) with one dormant 6.1 piece (the `sendRedirect` overloads `CapturingResponseWrapper` overrides for the 6.1 buffer-clearing redirect variants), and a bytecode scan of the servlet-MVC path (spring-web/-webmvc/Boot servlet layer, 2026-08-30) found NO hard Servlet 6.1 invocation either — the only real ones in spring-web sit in the reactive-on-servlet adapter, which MVC never touches. A WAR on an EE 10 WildFly might therefore start, but it runs without any guarantee from Spring (every patch release may adopt 6.1 API) and untested here. The boundary lifts properly when Undertow ships Servlet 6.1 |
| Jakarta Servlet API on the runtime classpath | `provided` scope in the module; the host's container supplies it |
| Java 21, Kotlin stdlib | the module is written in Kotlin; a Java host only needs `kotlin-stdlib`, which the jar pulls transitively |
| SLF4J 2.x binding (Logback by default in Boot) | the module uses the fluent `LoggingEventBuilder` API (`addKeyValue`) |
| Micrometer core | present via `spring-boot-starter-web`; an actuator `MeterRegistry` is optional |
| Spring MVC | optional — `endpoint_url_template`, the async pass and the worker MDC need it; the module depends on `spring-web` only and degrades gracefully without MVC |

The module is a **library**, not a starter: it declares `spring-boot-autoconfigure`, `slf4j-api`,
`spring-web`, `micrometer-core`, `kotlin-stdlib` and the `provided` servlet API — no logging backend, no
YAML, no container are forced onto the host.

### 3.2 Adding the dependency

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>limesium-servlet-logging</artifactId>
    <version><!-- current release: see the badge below --></version>
</dependency>
```

The current release is shown live by the Maven Central badge:
[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/limesium-servlet-logging.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/eu.inqudium/limesium-servlet-logging)

That is all: the auto-configuration registers the filter and the listener, every exchange is logged on
the `http-exchange` logger at INFO, the request id comes from the `traceparent` trace id (traceless
exchanges read/echo `X-Correlation-Id` instead — ADR-0002), the `endpoint_*` keys are in the MDC for
the chain, and the six meters are registered in the host's `MeterRegistry` if one exists.

To remove the module again without touching the classpath:

```yaml
endpoint-logging:
  enabled: false
```

### 3.3 Filter order and other filters

The filter is registered at `Ordered.HIGHEST_PRECEDENCE + 10`. The chain runs in ascending order, so:

- Boot's `ServerHttpObservationFilter` (`HIGHEST_PRECEDENCE + 1`) wraps this filter — the trace context
  itself comes from the `traceparent` header, not from that filter's MDC, but running inside the
  observation keeps the exchange event within the server span's timing
  ([§6.9](#69-the--10-order-is-load-bearing));
- everything ordered after `+ 10` — Spring Security, the application's own filters, the
  `DispatcherServlet` — runs **inside** the chain-wide MDC scope and sees `endpoint_request_id`;
- on a traceless exchange the correlation id is echoed on the response before any later filter can
  commit it (a traced exchange writes no header at all — ADR-0002).

Path activation is evaluated **in the filter** (`shouldNotFilter`), not via the registration's URL
patterns, so its semantics are byte-identical with the reactive twin. If the host needs a different
order, define its own `FilterRegistrationBean<RequestLoggingFilter>`.

### 3.4 Overriding beans

Every default is `@ConditionalOnMissingBean`:

```kotlin
@Configuration(proxyBeanMethods = false)
class EndpointLoggingCustomisation {

    /** Deterministic ids in a test profile, or a different id format. */
    @Bean
    fun correlationIdGenerator(): CorrelationIdGenerator =
        CorrelationIdGenerator { "req-" + ULID.random() }

    /** Only if the host owns a monotonic clock abstraction already. */
    @Bean
    fun nanoTimeSource(clock: MonotonicClock): NanoTimeSource =
        NanoTimeSource { clock.nanos() }
}
```

A host-defined `RequestLoggingFilter` bean replaces the **filter**, not the wiring: the auto-configured
`FilterRegistrationBean` (order) and the `ServletListenerRegistrationBean` (the emission point) are still
registered around it, so the emission at request destruction stays intact. The constructor takes
`(RequestLoggingProperties, NanoTimeSource, CorrelationIdGenerator, MeterRegistry)`:

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
([§6.10](#610-one-metrics-instance-per-registry)).

### 3.5 Logging backend and structured output

The module emits through SLF4J's fluent API. Every exchange event carries its data in **two places**, and
an encoder treats them differently:

| Data | Carried as | Examples |
|---|---|---|
| The field family | SLF4J **key-value pairs** (`addKeyValue`) | `endpoint_outcome`, `endpoint_duration_ms`, `endpoint_url_path`, `endpoint_request_body` |
| The identity and trace context | **MDC** entries, set by the emission scope (and, for the chain, by the chain scope) | `endpoint_request_id`, `endpoint_method`, `endpoint_route`, `traceId`, `parentSpanId` (from the `traceparent` header) |

A plain `%msg` pattern shows neither — only the message, which repeats the gist inline
(`… -> 200 [endpoint_request_id=…]`) precisely for that case. Logback offers three ways to render the
rest; which one fits depends on where the output goes.

#### Option 1 — `PatternLayout` with `%kvp` and `%mdc` (text, for terminals and files)

Logback ≥ 1.3 renders the key-value pairs with the `%kvp` conversion word and the MDC with `%mdc`
(all entries) or `%X{key}` (one entry):

```xml
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg %kvp{NONE} [%mdc]%n</pattern>
    </encoder>
</appender>
```

```
13:54:58.534 INFO  [http-nio-8080-exec-3] http-exchange - Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=4bf9… traceId=4bf9… parentSpanId=00f0…] endpoint_outcome=success endpoint_duration_ms=17 endpoint_request_method=GET endpoint_url_path=/api/things/42 endpoint_url_template=/api/things/{id} endpoint_response_status_code=200 endpoint_async=false [endpoint_method=GET, endpoint_request_id=4bf9…, endpoint_route=/api/things/42, traceId=4bf9…, parentSpanId=00f0…]
```

- `%kvp` quotes values with double quotes by default; `%kvp{NONE}` leaves them bare, `%kvp{SINGLE}` uses
  single quotes.
- `%X{endpoint_request_id:-}` prints one key and nothing when it is absent; `%mdc` prints every entry
  that is present as `key=value`, so the trace keys appear only on traced exchanges. A conditional prefix
  ("`traceId=` only when present") is not expressible in a pattern — use `%mdc` or a structured encoder.
- In Spring Boot the same pattern goes into `logging.pattern.console` without any XML.
- This is the module's own test configuration (`src/test/resources/logback-test.xml`), so a test run
  shows the complete event.
- **Text output renders values raw.** The logged path and query are percent-encoded as sent, but bodies
  (opt-in) may contain line breaks — see the security audit's CWE-117 notes before pointing a text
  appender at a log pipeline that parses lines.

#### Option 2 — Logback's `JsonEncoder` (JSON without an extra dependency, Logback ≥ 1.4.3)

```xml
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="ch.qos.logback.classic.encoder.JsonEncoder">
        <withSequenceNumber>false</withSequenceNumber>
        <withNanoseconds>false</withNanoseconds>
    </encoder>
</appender>
```

One JSON object per event, control characters escaped — but the key-value pairs arrive as a **list of
single-key objects** (`"kvpList":[{"endpoint_outcome":"success"},…]`) and the MDC nested under
`"mdc":{…}`. Correct and safe, yet awkward to map onto the flat `endpoint_*` fields of the index
template; suitable for local JSON inspection, not the recommended shape for an index.

#### Option 3 — Spring Boot structured logging (JSON, flat, typed — recommended for an index)

Boot ≥ 3.4 ships `StructuredLogEncoder`, configured without XML:

```yaml
logging:
  structured:
    format:
      console: ecs      # or logstash, gelf
  level:
    http-exchange: INFO
    eu.inqudium.limesium.servlet.logging: WARN
```

Key-value pairs and MDC entries become **flat top-level fields**, and values keep their JVM type —
`endpoint_duration_ms` is a number, `endpoint_response_status_code` a number, which is what the
type assertion in `EndpointLogField` guarantees on the producing side:

```json
{"@timestamp":"2026-08-23T13:54:58.534Z","log.level":"INFO","message":"Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=0f7c…]","endpoint_outcome":"success","endpoint_duration_ms":17,"endpoint_request_method":"GET","endpoint_url_path":"/api/things/42","endpoint_url_template":"/api/things/{id}","endpoint_response_status_code":200,"endpoint_request_id":"0f7c…","endpoint_method":"GET","endpoint_route":"/api/things/42","ecs.version":"8.11"}
```

This is the shape the component template in [§3.6](#36-index-mapping-elk) is written for. The same encoder is
available in XML as `<encoder class="org.springframework.boot.logging.logback.StructuredLogEncoder"><format>ecs</format></encoder>`,
and `logging.structured.json.include` / `exclude` / `rename` control the field selection (e.g. to drop
`endpoint_route`, which duplicates `endpoint_url_path`). Where MDC entries land in the document — flat,
nested, renamed — is this encoder configuration's decision, which is why the index template maps only
the key-value family and leaves the MDC keys to the host.

A fourth option, `logstash-logback-encoder`'s `LogstashEncoder`, also renders the key-value pairs flat
(`<includeKeyValuePairs>`), but needs an additional dependency — sensible only if the host uses it already.

| Option | Output | Key-value pairs | MDC | Typed values | Escapes control chars | Use for |
|---|---|---|---|---|---|---|
| 1 `PatternLayout` `%kvp` `%mdc` | text | inline `k=v` | inline `k=v` | no (all text) | **no** | terminals, local files, tests |
| 2 `JsonEncoder` | JSON | list of objects | nested `mdc` | partly | yes | local JSON inspection |
| 3 `StructuredLogEncoder` | JSON | flat fields | flat fields | **yes** | yes | **log index (ELK etc.)** |

Whatever the option, keep the `eu.inqudium.limesium.servlet.logging` logger at WARN or lower: it carries the
WARN breadcrumb on a thrown chain and the module's own failure reports.

### 3.6 Index mapping (ELK)

The thirteen `endpoint_*` fields have a ready-made Elasticsearch component template in
[`/docs/elk/`](../../docs/elk/README.md):

```bash
curl -X PUT "$ES/_component_template/limesium-servlet-logging-fields" \
     -H 'Content-Type: application/json' \
     --data-binary @docs/elk/limesium-servlet-logging-fields.component-template.json
```

Compose it into the data-stream mapping **before** the first event arrives — an unmapped body or header
field would be mapped dynamically and become searchable, which the payload fields' `index: false`
deliberately prevents. The MDC-carried keys are intentionally not in the template: where they land
depends on the host's encoder layout; map them where the encoder configuration lives.

### 3.7 Verifying the integration

1. Start the application and call any endpoint:

   ```bash
   curl -i -H 'X-Correlation-Id: demo-1' http://localhost:8080/api/things/42
   ```

   Expect `X-Correlation-Id: demo-1` on the response and one `http-exchange` line with
   `endpoint_request_id=demo-1`. With a `traceparent` header instead
   (`curl -i -H 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01' …`), expect
   **no** `X-Correlation-Id` response header and `endpoint_request_id=4bf92f…` plus
   `traceId=… parentSpanId=…` on the line (ADR-0002).

2. Log something inside the controller and confirm `endpoint_request_id` is on that line too.

3. Throw from a handler and confirm: an immediate WARN breadcrumb on
   `eu.inqudium.limesium.servlet.logging.RequestLoggingFilter`, then the exchange line with the rendered `500`,
   `endpoint_outcome=failure` at ERROR with the cause attached.

4. Check the meters (with actuator):

   ```bash
   curl -s localhost:8080/actuator/metrics/endpoint.logging.events
   curl -s localhost:8080/actuator/metrics/endpoint.logging.exchanges.open
   ```

   `events` should equal the number of logged lines; `exchanges.open` should be `0` when idle.

---

## 4. Configuration

All properties live under `endpoint-logging.*`. The complete, commented reference with every default is
[`/docs/endpoint-logging-reference.yml`](../../docs/endpoint-logging-reference.yml); `EndpointLoggingReferenceConfigTest`
binds it against `RequestLoggingProperties` and fails the build on any drift — every key must exist,
every value must be the built-in default. The reactive twin binds the same file, so the namespace is
identical across the stacks (the twin adds one reactive-only key, `variant`, which this module ignores).

### 4.1 Property reference

| Property | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch. `false` makes the auto-configuration back off — no filter, no listener, no beans. A context-start decision, not a runtime toggle. |
| `logger-name` | string | `http-exchange` | Logger of the arrival line and the exchange event. Its level is the runtime volume control ([§4.5](#45-logger-levels)). |
| `correlation-id-header` | string (RFC 9110 token) | `X-Correlation-Id` | Header the correlation id is read from on **traceless** exchanges (no conformant `traceparent` — ADR-0002); blank/absent means generated. Only such an exchange gets the echo, set once at filter entry — downstream code that sets the header itself or calls `response.reset()` decides what the client finally sees; event and MDC keep the id resolved at entry. A traced exchange takes its request id from the `traceparent` trace id, ignores this header and echoes nothing. |
| `include-query-string` | boolean | `true` | Log the query string as its own field `endpoint_url_query` (never part of the path). Disable when query parameters may carry personal data. |
| `log-request-start` | boolean | `false` | Additionally log an arrival line before the chain runs, at INFO, inside the chain MDC scope. Carries no outcome/status/duration. |
| `include-path-patterns` | list of `PathPattern` | `[]` | Endpoints the filter is active for at all; empty = every endpoint. Parsed once at startup; an invalid pattern fails the context. |
| `exclude-path-prefixes` | list of strings | `[]` | Request-URI prefixes the filter skips entirely — no event, no MDC, no correlation echo, no gauge movement. Prefix match against the decoded request path. An exclude always wins over an include. |
| `slow-request-threshold` | duration | `5s` | At/above this duration an INFO exchange escalates to WARN and is flagged `endpoint_slow: true`; the outcome stays `success`. Measured as request occupancy ([§6.2](#62-duration-is-request-occupancy)). Must be ≥ 1 ms. |
| `request-headers.includes` / `.excludes` / `.masked` | lists of header names | `[]` | See [§4.2](#42-header-sections). |
| `response-headers.includes` / `.excludes` / `.masked` | lists of header names | `[]` | See [§4.2](#42-header-sections). |
| `log-request-body` | boolean | `false` | Tee the request body into `endpoint_request_body`, up to `max-body-bytes`. |
| `log-response-body` | boolean | `false` | Tee the response body into `endpoint_response_body`, up to `max-body-bytes`. |
| `measure-request-body-size` | boolean | `false` | Record `endpoint.request.body.size`; independent of `log-request-body`. |
| `measure-response-body-size` | boolean | `false` | Record `endpoint.response.body.size`; independent of `log-response-body`. |
| `max-body-bytes` | int > 0 | `16384` | Capture limit per body. Bounds **memory**, not the exchange: bytes beyond it still flow; the logged value is truncated with a note of the total size. |

### 4.2 Header sections

Each direction has one section with three lists; matching is case-insensitive throughout.

| List | Semantics |
|---|---|
| `includes` | Names to log. **Empty logs nothing** (the safe default). The entry `*` logs every header the message carries, deduplicated case-insensitively (servlet enumerations may repeat names). |
| `excludes` | Names removed from the included set — meaningful mainly with `*`. An exclude always wins. `*` is rejected here at binding time (an empty `includes` already logs nothing). |
| `masked` | Names whose **value** is replaced by a fingerprint `length:hex` — the character length plus the first 64 bits of the SHA-256 of the UTF-8 value, e.g. `18:930bbdc51b6aed5c`. `*` masks every logged header. Masking affects only headers that are logged; listing a name here does not include it. |

Multi-valued headers are resolved through `getHeaders(name)` and joined with `, ` — a single-value
`getHeader` would silently truncate repeated headers (`Set-Cookie` being the classic). The selected pairs
are rendered into one display-only field per direction as `[Name:"value", Name2:"value2"]`; nothing is
emitted when the selection is empty or no selected header is present.

Request headers are selected at **filter entry**; response headers at **request destruction**, so they
reflect what the chain and the container's error rendering set.

### 4.3 Body logging and body measuring

Four independent flags, two per direction:

| `log-*-body` | `measure-*-body-size` | Wrapper installed | Buffered | Effect |
|---|---|---|---|---|
| off | off | no | — | chain gets the original request/response, zero overhead |
| on | off | yes, limit `max-body-bytes` | up to the limit | field logged; no size sample |
| off | on | yes, limit `0` (count-only) | nothing | size sample recorded; no field |
| on | on | yes, limit `max-body-bytes` | up to the limit | both |

Rules that hold for every combination:

- The tee is passive: bytes are counted and (up to the limit) copied as they flow; nothing is pre-read,
  replayed or withheld. Streaming and async behaviour are untouched.
- An **unread request body** is logged as absent; no size sample is recorded.
- Zero-byte bodies produce no field and no sample — the distribution describes bodies that exist.
- Truncation is **byte-bounded**, and the decoder leaves an incomplete trailing multi-byte sequence
  undecoded rather than rendering a replacement character: `…<prefix>... [truncated, 12345 bytes total]`.
- The log charset is the declared request/response encoding, UTF-8 when absent or unparsable
  ([§6.7](#67-request-charset-log-rendering-vs-the-servlet-contract)).
- `measure-*` records what actually flowed, **exact beyond** `max-body-bytes`.
- `measure-request-body-size` additionally records `endpoint.request.body.read` — whether the application
  consumed the body completely, partially, or not at all ([§5.4](#54-meters)).
- The capture follows the response buffer: `reset()`, `resetBuffer()`, `sendError` and buffer-clearing
  redirects discard it ([§6.4](#64-buffer-clearing-operations-discard-the-capture)).

### 4.4 Path activation

```
active(requestURI) = (include-path-patterns is empty  OR  any pattern matches requestURI)
                     AND no exclude-path-prefix is a prefix of requestURI
```

An inactive request passes through **without any trace**: no correlation echo, no MDC, no event, no
gauge movement, no counters. Typical use:

```yaml
endpoint-logging:
  include-path-patterns:
    - /api/**
  exclude-path-prefixes:
    - /actuator/health
    - /actuator/prometheus
```

`include-path-patterns` uses Spring's `PathPattern` syntax (`/api/**`, `/api/{*rest}`,
`/files/{id}.pdf`); `exclude-path-prefixes` is a prefix match. Both see the request target the way
Spring MVC routes it — the **path within the application** (a configured `server.servlet.context-path`
is stripped first, exactly as in MVC's handler mapping), parsed into segments that **decode for
matching** and drop path parameters — so `/api/**` matches `/app/api/things` under context path
`/app`, `/%61pi/things` is included by `/api/**` and `/%61ctuator/health` is excluded by
`/actuator/health`, exactly as the router would serve them. The logged `endpoint_url_path` stays raw
and keeps the context path.

### 4.5 Logger levels

Severity and semantic are decoupled: the level only decides how loud — and whether — a line is emitted;
`endpoint_outcome` carries the disposition ([§5.3](#53-levels-and-outcomes)). The level of the
`logger-name` logger therefore acts as the runtime volume control:

| `http-exchange` level | Emitted |
|---|---|
| `INFO` | every exchange |
| `WARN` | failures (5xx), container timeouts, slow exchanges — and thrown chains |
| `ERROR` | only exchanges whose chain threw or whose async cycle errored |
| `OFF` | nothing — and no event is even assembled |

Level and outcome are resolved **before** the event is built, so a disabled level costs no assembly, no
header selection, no body decoding. Metrics are recorded **before** the level gate and are unaffected by
it — except `endpoint.logging.events`, which by definition counts emitted events only.

### 4.6 Validation at startup

`RequestLoggingProperties.init` and `HeaderLogProperties.init` reject, with a message naming the
property:

- blank `logger-name` or `correlation-id-header`;
- a `correlation-id-header` that is not an RFC 9110 token (it is written to every response; a non-token
  would be rejected per request by a strict container and silently turn the filter into an unlogged
  pass-through);
- `max-body-bytes` ≤ 0;
- `slow-request-threshold` < 1 ms (the logged duration has millisecond resolution);
- blank entries in any list;
- `*` in an `excludes` list;
- an unparsable `include-path-patterns` entry (parsed once at filter construction).

### 4.7 Example configurations

**Minimal production profile** — everything logged, health probes excluded, slow threshold tightened:

```yaml
endpoint-logging:
  exclude-path-prefixes:
    - /actuator/health
  slow-request-threshold: 2s
logging:
  level:
    http-exchange: INFO
    eu.inqudium.limesium.servlet.logging: WARN
```

**Diagnostics profile** — headers with masked credentials, request bodies, arrival lines:

```yaml
endpoint-logging:
  log-request-start: true
  log-request-body: true
  max-body-bytes: 16384
  request-headers:
    includes: ["*"]
    excludes: [Cookie]
    masked: [Authorization, X-Api-Key]
  response-headers:
    includes: [Content-Type, Content-Length, Set-Cookie]
    masked: [Set-Cookie]
```

**Metrics without log volume** — body sizes measured, only failures logged:

```yaml
endpoint-logging:
  measure-request-body-size: true
  measure-response-body-size: true
logging:
  level:
    http-exchange: WARN
```

**API-only scope with a custom correlation header:**

```yaml
endpoint-logging:
  correlation-id-header: X-Request-Id
  include-path-patterns:
    - /api/**
  include-query-string: false
```

---

## 5. Metrics and observation

### 5.1 Log fields

The structured fields of the completion event (the arrival line carries only method, path, query and
request headers). The index types are those of the shipped component template; `EndpointLogFieldTest`
keeps the enum in lockstep with it in both directions.

| Field | Type | Index | doc_values | When present | Notes |
|---|---|---|---|---|---|
| `endpoint_outcome` | keyword | yes | on | always | `success` / `failure` / `timeout` — the field dashboards split by; decoupled from the level |
| `endpoint_duration_ms` | long | yes | on | always | from the injected monotonic source; request occupancy until destruction |
| `endpoint_request_method` | keyword | yes | on | always | |
| `endpoint_response_status_code` | short | yes | on | always | the final status at destruction — a numeric label, never summed |
| `endpoint_url_path` | keyword | yes | **off** | always | expanded path, ids and all — filter exactly, never group |
| `endpoint_async` | boolean | yes | on | always | `true` when the chain returned with async processing started |
| `endpoint_url_template` | keyword | yes | on | when Spring MVC recorded a handler pattern | the aggregation half of the path pair, e.g. `/api/things/{id}` |
| `endpoint_url_query` | keyword | yes | **off** | when the request had one and `include-query-string` is on | |
| `endpoint_slow` | boolean | yes | on | only when the threshold was reached | absence means fast |
| `endpoint_request_headers` | keyword | **no** | off | when selected headers are present | display only, rendered `[Name:"value", …]` |
| `endpoint_response_headers` | keyword | **no** | off | when selected headers are present | display only |
| `endpoint_request_body` | keyword | **no** | off | when `log-request-body` is on and bytes flowed | display only, bounded |
| `endpoint_response_body` | keyword | **no** | off | when `log-response-body` is on and bytes flowed | display only, bounded |

Each field asserts the exact JVM type of its value (`EndpointLogField.format`): a wrongly typed value
drops **that field** with a warning on `eu.inqudium.limesium.servlet.logging.EndpointLogField`, never the event.

The throwable of a failed chain — or of an async `onError`/`onTimeout` event, when the container
supplied one — is attached to the event as its cause (`setCause`), so a structured encoder renders the
stack trace alongside the fields.

### 5.2 MDC keys

| Key | Value | Scope |
|---|---|---|
| `endpoint_request_id` | the request id: the `traceparent` trace id, or the accepted/generated correlation id (ADR-0002) — always set | chain, MVC async worker, async dispatch, emission |
| `endpoint_method` | the HTTP method | same |
| `endpoint_route` | the request **path** (the template is not known at filter entry) | same |
| `traceId` | the trace id parsed from the `traceparent` header | emission only (a bridge owns the key during the chain) |
| `parentSpanId` | the caller's span id parsed from the `traceparent` header — never published as `spanId` | emission only |

`MdcScope` restores the previous value of every key on close, rolls back a partial install if the
adapter throws mid-put, and restores best-effort on close with the first failure rethrown and later ones
suppressed.

### 5.3 Levels and outcomes

Resolved in this order in `ExchangeLogEmitter`:

| Condition | Level | `endpoint_outcome` | Cause attached |
|---|---|---|---|
| the chain threw (initial or async dispatch) | `ERROR` | `failure` | the exception |
| async disposition `TIMED_OUT` | `WARN` | `timeout` | the `onTimeout` throwable, if any |
| async disposition `ERRORED` | `ERROR` | `failure` | the `onError` throwable, if any |
| status ≥ 500 without any of the above (the application handled it) | `WARN` | `failure` | — |
| otherwise | `INFO` | `success` | — |
| … and the duration reached `slow-request-threshold` | `INFO → WARN` | unchanged, plus `endpoint_slow: true` | — |

Slowness raises severity; it never turns a completed exchange into a failure. `timeout` wins over a
subsequent `onError` (the container aborting the timed-out cycle) by construction of `AsyncDisposition`.

### 5.4 Meters

Six meters, all **consumed** from the host's `MeterRegistry` (an `ObjectProvider`; without one a
private `SimpleMeterRegistry` absorbs the values). All fixed-tag meters are **pre-registered at
construction**, so a `rate()` alert sees the zero before the first occurrence. Rates, latencies and
status distributions are deliberately left to `http.server.requests` and the log fields.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `endpoint.logging.failopen` | counter | `stage` = `emission` \| `arrival` \| `wiring` | Logging failures the fail-open path swallowed. `emission`: an exchange event was **lost**. `arrival`: a start line was lost. `wiring`: bookkeeping failed (pass-through degradation, a lost MDC scope, a lost sample or counter) — the event usually still follows. A lost log line cannot report itself through the same pipeline; this counter is the independent channel. |
| `endpoint.logging.events` | counter | `outcome` = `success` \| `failure` \| `timeout` | Exchange events actually **emitted** on the exchange logger — after the level gate, arrival lines excluded. The reconciliation ground truth against the log index. |
| `endpoint.logging.exchanges.open` | gauge | — | Exchanges between filter entry and request destruction. Hovers near the active-request count in health. |
| `endpoint.logging.correlation.id` | counter | `source` = `trace` \| `header` \| `generated` | Origin of each exchange's request id (ADR-0002); the meter name predates the decision and stays stable. |
| `endpoint.request.body.read` | counter | `uri` = handler pattern, `UNKNOWN` without one; `state` = `unread` \| `partial` \| `complete` | How far the application **consumed** the request body, opt-in via `measure-request-body-size`. Recorded once per exchange whenever the measuring tee exists — including bodyless requests the application never touched, which is the `unread` share the counter exists to show. `partial` = consumption started but the end of the stream was never observed (an early-exiting parser, an exception mid-read, a read loop that never asked for the final EOF). Created lazily per `uri`/`state` on first use, like the size summaries. |
| `endpoint.request.body.size` / `endpoint.response.body.size` | distribution summary, base unit `bytes` | `uri` = handler pattern, `UNKNOWN` without one | Bytes that **actually flowed**, opt-in via `measure-*-body-size`, independent of body logging and level. Exact beyond `max-body-bytes`. Zero-byte bodies record no sample. Created lazily per `uri` on first use. |

**Registration conflicts.** Micrometer rejects a registration whose id already exists with a different
meter type. Rather than aborting the context (at construction) or suppressing an exchange event (at the
lazy body-size registration), the conflicting meter falls back to a private registry, warned once per
meter name on `eu.inqudium.limesium.servlet.logging.EndpointLoggingMetrics`: the module keeps working and that
meter is simply not exported.

### 5.5 Reading the meters together

The meters are designed to cover each other's blind spots:

| Question | Signal |
|---|---|
| Are exchange events being lost **loudly** (something threw)? | `failopen{stage=emission}` > 0 |
| Are exchange events being lost **silently** (`requestDestroyed` not firing — nothing throws, so no fail-open count)? | `exchanges.open` baseline grows monotonically instead of returning towards 0 |
| Is the **log pipeline** (appender, broker, index) losing events? | `sum(endpoint.logging.events)` over a window ≠ count of indexed `http-exchange` documents for the same window |
| Did the upstream stop propagating correlation ids? | the `generated` share of `correlation.id` rises |
| Are async cycles timing out? | `events{outcome="timeout"}` |
| Is an endpoint ignoring or abandoning the payload it is handed? | the `unread` or `partial` share of `request.body.read{uri=...}` rises — the logged body and the size sample cannot show this, both describe only what was consumed |
| Are payloads growing beyond what the log captures? | `body.size` percentiles vs. `max-body-bytes` |

A suggested alert set:

```promql
# lost exchange events (hard failure)
increase(endpoint_logging_failopen_total{stage="emission"}[5m]) > 0

# silently stuck exchanges (liveness) - tune the bound to the service's concurrency
min_over_time(endpoint_logging_exchanges_open[15m]) > 50

# correlation contract regression
sum(rate(endpoint_logging_correlation_id_total{source="generated"}[10m]))
  / sum(rate(endpoint_logging_correlation_id_total[10m])) > 0.2
```

### 5.6 Trace correlation

The trace context comes from the incoming W3C `traceparent` header, parsed by this module at filter
entry with the full W3C validation (ADR-0002; the parser is the shared `Traceparent` from
`limesium-common`, inlined into this jar — ADR-0003). The header's trace id is the trace the server span runs under, so
the log-to-trace join holds; the header's parent-id is the **caller's** span and is published as
`parentSpanId`, never as `spanId`, where it would read as the local span. The destruction callback's
thread carries no per-request state, so the emission `MdcScope` restores the parsed pair around the
event — as MDC fields for structured encoders, and inline in the message
(`… traceId=… parentSpanId=…`) for plain-text appenders.

The emission scope **owns** the trace keys, a bridge's `spanId` included: a parsed id is installed, an
unparsed one is removed for the scope's lifetime, so a stale id on the pooled destruction thread can
never join the event to a foreign trace or span. Without a (valid) `traceparent`, nothing is decorated —
a trace the bridge mints locally is deliberately not joined; such an exchange carries a generated
request id instead.

The ids ride the MDC only, never the key-values,
so the log-to-trace join uses Boot's standard `traceId` key. `RequestLoggingFilterTomcatTracingIntegrationTest`
pins the parsed context and the identity decision against a real Brave bridge running beside it.

---

## 6. Special characteristics

### 6.1 Differences to the reactive twin

Everything not listed here behaves identically in `limesium-reactive-logging`.

| Concern | This module | Reactive twin |
|---|---|---|
| Disposition vocabulary | `success` / `failure` / **`timeout`** (container async timeout) | `success` / `failure` / `cancelled` (client disconnect) |
| `endpoint_async` | emitted, always | never emitted |
| `endpoint_response_status_code` | always present | absent for a never-committed cancellation |
| Emission point | `requestDestroyed`, after the error dispatch and async completion | terminal signal; commit-deferred on error |
| Chain-wide MDC | thread-local, for the whole chain, plus the MVC async worker | Reactor context + opt-in accessors, or `MDCContext` in the coroutine variant |
| Body tee | stream/reader and stream/writer wrappers; `reset()`/`sendError` clear the capture | `DataBuffer` map-tee; no reset analog |
| Body capture concurrency | single writer, late reader; volatile total as the happens-before edge | lock-guarded, frozen at emission (late chunks after cancellation) |
| Variant selection | one filter | `endpoint-logging.variant` |
| Handler template attribute | Spring MVC's `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` (mirrored as a constant; pinned by `HandlerMappingAttributeTest`) | WebFlux's |

### 6.2 Duration is request occupancy

`endpoint_duration_ms` is measured from filter entry to **request destruction**: it includes the
container's error rendering and, for an async exchange, the whole time the request was parked waiting
for the result. It is not bare handler time and not what `http.server.requests` measures. The slow
threshold is compared against this occupancy.

### 6.3 Container error rendering bypasses the response tee

`OncePerRequestFilter` skips the **ERROR dispatch**, so the final body of a container error page (Boot's
error page after `sendError` or an unhandled exception) is written through the **original** response.
The event still carries the rendered **status** (read at destruction), but `endpoint_response_body` and
the response-size sample stay absent for container-rendered error responses. Responses rendered locally —
a controller's `ResponseEntity`, a `@ControllerAdvice` — traverse the tee normally. Pinned by integration
test.

### 6.4 Buffer-clearing operations discard the capture

A reset of an **uncommitted** response discards everything buffered — nothing written so far ever
reached the client — so the capture is discarded with it; otherwise the logged body and the size metric
would report bytes the client never saw (a partially written response an `@ExceptionHandler` throws away
and rewrites). This applies to `reset()`, `resetBuffer()`, both `sendError` variants and the
buffer-clearing `sendRedirect` variants, which clear the buffer per the servlet spec without going
through the reset overrides. On a **committed** response `super.reset()` throws per spec and the capture
stays intact. A full `reset()` also drops the cached tee stream/writer, mirroring the servlet rule that
the previously returned object is stale.

### 6.5 Raw `startAsync()` bypasses the tee

The tee lives on the wrappers the filter passes down the chain. Spring MVC's async support calls
`startAsync(currentRequest, currentResponse)` and keeps the wrappers. The servlet-specified
**zero-argument** `startAsync()` initialises its context with the **original** request/response, so
bytes a raw async cycle reads or writes flow beside the tee and are logged as absent. A documented
boundary, pinned by integration test.

### 6.6 Async started and completed inside the chain

The filter reads `request.isAsyncStarted` in its `finally`. An async cycle that starts **and** completes
within the chain reads `false` there: such an exchange logs `endpoint_async=false` and gets no
`AsyncOutcomeMarker`, so a timeout or error inside that window would go unmarked. The servlet API offers
no portable "was async ever started" signal; accepted and documented.

### 6.7 Request charset: log rendering vs. the servlet contract

`CapturingRequestWrapper` preserves the servlet decoding contract **exactly** for the application: the
reader uses the declared request encoding, the spec default ISO-8859-1 when none is declared, and throws
`UnsupportedEncodingException` for an unsupported one — as an unwrapped request would. The **log** charset
is separate: UTF-8 when no encoding is declared (modern payloads without a declaration are far more
likely UTF-8), and it is bound **late** — when the application first selects the stream or the reader —
because the servlet contract lets downstream code call `setCharacterEncoding` until the body is consumed.
A charset frozen at filter entry would decode the captured bytes with an encoding the application never
used.

### 6.8 Writer fidelity and `checkError()`

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
<a id="69-the--10-order-is-load-bearing"></a>

### 6.9 The `+ 10` order is load-bearing

Boot registers `ServerHttpObservationFilter` at `Ordered.HIGHEST_PRECEDENCE + 1`, and the chain runs in
ascending order — so that filter wraps this one. Since ADR-0002 the trace context no longer depends on
that ordering (it is parsed from the `traceparent` header, not captured from the bridge's MDC), but the
order stays at `+ 10` deliberately: the exchange runs inside the server span's observation, the chain
MDC scope opens before Security and the application filters, and the traceless echo lands before any
later filter can commit the response. `RequestLoggingFilterTomcatTracingIntegrationTest` pins the parsed
context against a real bridge - its MDC writes and its own server span must never leak into the event.

### 6.10 One metrics instance per registry

Micrometer deduplicates meters by id. A second `EndpointLoggingMetrics` instance against the same
registry shares the **counters** (increments merge) but not the **gauge**: the second gauge registration
is silently ignored and that instance's open-exchange movements become invisible. The auto-configuration
creates exactly one filter and therefore one instance; a host wiring additional filter instances against
one registry inherits this limitation knowingly.

### 6.11 Masking is a fingerprint, not a secret

`masked` replaces a header value with `length:sha256-prefix64` — stable, so a masked token can still be
correlated across events and modules (the reactive twin uses the same
scheme), and a 64-bit cryptographic prefix makes accidental collisions negligible. It is **unsalted and
unkeyed**: it prevents plaintext exposure, not offline guessing. A reader with a candidate list
(usernames, tenant names, short API keys) can confirm a candidate by hashing it. Do not treat `masked` as
a security boundary for guessable values; omit such headers from the selection instead.

### 6.12 Shared code: limesium-common, inlined by Shade

The BYTE-identical part of the twins' shared layer lives in the `limesium-common` module
([ADR-0003](../../docs/adr/ADR-0003-limesium-common-inlined-by-shade.md)): the `Traceparent` parser
(with its tests and fuzz target), `NanoTimeSource`, `CorrelationIdGenerator`, `reportQuietly`, and the
MDC keys and scope. The Maven Shade plugin inlines those classes into THIS jar at package time, the
dependency-reduced POM drops the dependency, and `limesium-common` is never published — consumers keep
adding exactly one artifact, and the shared classes stay `internal` (`-Xfriend-paths`).

Everything whose twin copies genuinely differ stays deliberately duplicated, per the original
architecture-review decision: the field enum and metrics (per-stack outcome vocabulary and meter
descriptions), the emitters and exchanges, the properties (`variant` is reactive-only), and
`BoundedBodyCapture` (two different concurrency designs). For those the accepted cost is unchanged: a
change is a conscious port in **both** directions, and the lockstep tests catch *named* contract drift
(keys, field names, meter names, message text), not behavioural drift inside near-identical code.

---

## 7. Appendix

### 7.1 File map

```
limesium-servlet-logging/
├── pom.xml                                   library deps only; servlet API provided
├── README.md                                 module summary, twin decision
├── docs/
│   ├── activity-diagram.svg                  UML activity diagram of one exchange
│   └── GUIDE.md                              this document
└── src/
    ├── main/kotlin/eu/inqudium/limesium/servlet/logging/
    │   ├── RequestLoggingAutoConfiguration.kt     filter, registration, listener, defaults
    │   ├── RequestLoggingProperties.kt            endpoint-logging.* binding, HeaderLogProperties
    │   ├── RequestLoggingFilter.kt                the servlet lifecycle, completion listener
    │   ├── Exchange.kt                            per-exchange state, AsyncDisposition, AsyncOutcomeMarker
    │   ├── EndpointMdcCallableInterceptor.kt      MDC on the MVC async worker
    │   ├── ExchangeLogEmitter.kt                  arrival line and completion event
    │   ├── EndpointLogFields.kt                   field enum and builder helpers (owns the family)
    │   ├── EndpointLoggingMetrics.kt              the six meters
    │   ├── CapturingRequestWrapper.kt             request stream/reader tee
    │   ├── CapturingResponseWrapper.kt            response stream/writer tee
    │   ├── BoundedBodyCapture.kt                  bounded capture target, BodyReadState
    │   └── EndpointLogFields.kt … (see above)     Traceparent, Mdc, NanoTimeSource,
    │                                              CorrelationIdGenerator and reportQuietly live in
    │                                              ../limesium-common (inlined into this jar, §6.12)
    ├── main/resources/META-INF/spring/…AutoConfiguration.imports
    └── test/kotlin/eu/inqudium/limesium/servlet/logging/   unit, async, tracing (real Brave bridge), integration (real Tomcat AND real Jetty - the capture boundaries are pinned per container), lockstep tests
```

### 7.2 Related documents

- [`README.md`](../README.md) — module summary and the twin decision.
- [`/docs/endpoint-logging-reference.yml`](../../docs/endpoint-logging-reference.yml) — the complete commented
  configuration reference; every key and default, bound by `EndpointLoggingReferenceConfigTest` here and
  in the twin.
- [`/docs/elk/README.md`](../../docs/elk/README.md) — the Elasticsearch component template for the `endpoint_*`
  fields and the access pattern behind each mapping decision.
- [`limesium-reactive-logging/docs/GUIDE.md`](../../limesium-reactive-logging/docs/GUIDE.md) — the
  twin's guide; [§6.1](#61-differences-to-the-reactive-twin) lists every deliberate difference.
