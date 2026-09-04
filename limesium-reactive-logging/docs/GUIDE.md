# limesium-reactive-logging — Guide

One structured `endpoint_*` log line per HTTP exchange in a Spring WebFlux application — with the same
message format, the same field family, the same `endpoint-logging.*` configuration and the same meters
as the servlet twin [`limesium-servlet-logging`](../../limesium-servlet-logging/README.md).

This guide is the long-form companion to the module [README](../README.md). It explains what the module
does, how it is built, how to drop it into a foreign application, what can be configured, what it
measures, and which behaviours are specific to the reactive stack. Everything here is derived from the
code under `src/main/kotlin/eu/inqudium/limesium/reactive/logging/`; when the two disagree, the code wins.

## Table of contents

1. [Introduction](#1-introduction)
   1. [What the module does](#11-what-the-module-does)
   2. [What the module deliberately does not do](#12-what-the-module-deliberately-does-not-do)
   3. [The exchange line](#13-the-exchange-line)
   4. [Relation to the servlet twin](#14-relation-to-the-servlet-twin)
2. [Architecture](#2-architecture)
   1. [Component overview](#21-component-overview)
   2. [Auto-configuration and variant selection](#22-auto-configuration-and-variant-selection)
   3. [Lifecycle of one exchange](#23-lifecycle-of-one-exchange)
   4. [Emission point: terminal signal, commit-deferred on error](#24-emission-point-terminal-signal-commit-deferred-on-error)
   5. [The body tee](#25-the-body-tee)
   6. [MDC and the Reactor context](#26-mdc-and-the-reactor-context)
   7. [Fail-open contract](#27-fail-open-contract)
   8. [Injectable collaborators](#28-injectable-collaborators)
3. [Using it in a foreign project](#3-using-it-in-a-foreign-project)
   1. [Prerequisites](#31-prerequisites)
   2. [Adding the dependency](#32-adding-the-dependency)
   3. [Choosing the filter variant](#33-choosing-the-filter-variant)
   4. [Enabling handler-side MDC](#34-enabling-handler-side-mdc)
   5. [Overriding beans](#35-overriding-beans)
   6. [Logging backend and structured output](#36-logging-backend-and-structured-output)
   7. [Index mapping (ELK)](#37-index-mapping-elk)
   8. [Verifying the integration](#38-verifying-the-integration)
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
   1. [Differences to the servlet twin](#61-differences-to-the-servlet-twin)
   2. [Cancellation and the missing status](#62-cancellation-and-the-missing-status)
   3. [Error rendering bypasses the response tee](#63-error-rendering-bypasses-the-response-tee)
   4. [Zero-copy responses](#64-zero-copy-responses)
   5. [Late body chunks after cancellation](#65-late-body-chunks-after-cancellation)
   6. [Coroutine boundary and exception copies](#66-coroutine-boundary-and-exception-copies)
   7. [One metrics instance per registry](#67-one-metrics-instance-per-registry)
   8. [Masking is a fingerprint, not a secret](#68-masking-is-a-fingerprint-not-a-secret)
   9. [Shared code: limesium-common, inlined by Shade](#69-shared-code-limesium-common-inlined-by-shade)
7. [Appendix](#7-appendix)
   1. [File map](#71-file-map)
   2. [Related documents](#72-related-documents)

---

## 1. Introduction

### 1.1 What the module does

`limesium-reactive-logging` is a Spring Boot auto-configured `WebFilter` for **reactive** (WebFlux)
applications. For every inbound HTTP exchange it:

- resolves the exchange identity per ADR-0002: a conformant `traceparent`'s trace id **is** the request
  id; only a traceless exchange adopts a correlation id from the configured request header (or
  generates one) and echoes it back on
  the response;
- optionally logs an **arrival line** the moment the request comes in;
- measures the exchange duration with an injectable monotonic time source;
- optionally tees the request and response bodies as they flow (bounded, never buffered or replayed);
- optionally records the selected request/response headers, with stable masking of sensitive values;
- parses the incoming W3C `traceparent` header for log-to-trace correlation — a traced exchange
  passes through observationally untouched (no echo);
- emits **exactly one** structured completion event on a dedicated logger, with the outcome, status,
  duration, path, handler template, and the optional headers/bodies as SLF4J key-values;
- feeds six Micrometer meters that observe the logging itself (fail-open counts, emitted events, open
  exchanges, body sizes, request-id origin).

It does all of this **fail-open**: no failure inside the logging — wiring, body tee, emission,
metrics, MDC adapter — can ever fail, delay, or alter the request it describes.

### 1.2 What the module deliberately does not do

- **No request rates, latencies or status distributions as metrics.** Boot's own
  `http.server.requests` and the structured log fields already cover those; the module's meters observe
  only what those cannot show (see [§5.4](#54-meters)).
- **No body masking or transformation.** Bodies are logged verbatim up to the capture limit. If a body
  may carry personal data, leave `log-*-body` off.
- **No sampling.** Every matching exchange emits one event; the logger level is the only volume
  control ([§4.5](#45-logger-levels)).
- **No exporting of a `MeterRegistry`.** The host's registry is consumed if present; otherwise a
  private `SimpleMeterRegistry` absorbs the values.
- **No chain-wide thread-local MDC by itself.** Reactive handlers hop event-loop threads; handler-side
  MDC is an opt-in that needs either the coroutine variant or Micrometer's context propagation
  ([§2.6](#26-mdc-and-the-reactor-context)).

### 1.3 The exchange line

On the logger `http-exchange` (configurable) a completed exchange looks like this in a plain-text
appender:

```
Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=0f7c1a2e-... traceId=4bf92f3577b34da6a3ce929d0e0e4736 parentSpanId=00f067aa0ba902b7]
```

The trace suffix appears only when the request carried a valid `traceparent` header. Alongside the
message, the event carries SLF4J key-values that a structured encoder (e.g. Logback's JSON encoders,
Boot's `StructuredLogEncoder`) turns into fields:

```json
{
  "message": "Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=0f7c1a2e-...]",
  "level": "INFO",
  "logger": "http-exchange",
  "endpoint_outcome": "success",
  "endpoint_duration_ms": 17,
  "endpoint_request_method": "GET",
  "endpoint_url_path": "/api/things/42",
  "endpoint_url_template": "/api/things/{id}",
  "endpoint_response_status_code": 200,
  "endpoint_request_id": "0f7c1a2e-...",
  "endpoint_method": "GET",
  "endpoint_route": "/api/things/42",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "parentSpanId": "00f067aa0ba902b7"
}
```

The `endpoint_request_id` / `endpoint_method` / `endpoint_route` / `traceId` / `parentSpanId` entries
come from the MDC (see [§5.2](#52-mdc-keys)); the `endpoint_*` key-values are the field family of
[§5.1](#51-log-fields). How MDC entries land in the document (flat, nested, renamed) is the encoder's
decision.

With the optional arrival line enabled, a second, earlier line precedes it:

```
Endpoint http exchange started GET /api/things/42 [endpoint_request_id=0f7c1a2e-...]
```

The arrival line carries no outcome, status or duration, so a dashboard keyed on `endpoint_outcome`
still sees exactly one event per exchange.

### 1.4 Relation to the servlet twin

The module is the **WebFlux twin** of `limesium-servlet-logging`. The servlet module is the reference
implementation and owns the cross-stack contract:

| Contract | Owner | Lockstep test in this module |
|---|---|---|
| Configuration keys and defaults | [`/docs/endpoint-logging-reference.yml`](../../docs/endpoint-logging-reference.yml) | `EndpointLoggingReferenceConfigTest` binds that YAML against this module's `RequestLoggingProperties` |
| Field family and index mapping | [`/docs/elk/…component-template.json`](../../docs/elk/README.md) | `EndpointLogFieldTest` locks this module's `EndpointLogField` enum against the template |
| Message text and meter names | the servlet module's emitter and metrics | `TwinContractTest` |

The build pulls those two files from the sibling checkout as **test resources** (declared in this
module's `pom.xml`), so a missing sibling fails at resource processing with a clear message rather than
as a silent contract drift. The consequence for a consumer: a dashboard, alert or index mapping written
for one stack works unchanged for the other.

---

## 2. Architecture

### 2.1 Component overview

The module is nineteen Kotlin files in one package, `eu.inqudium.limesium.reactive.logging`. They fall into five
layers:

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ Auto-configuration                                                               │
│   CoRequestLoggingAutoConfiguration  (before)  RequestLoggingAutoConfiguration   │
│   RequestLoggingProperties · Variant · HeaderLogProperties                       │
├──────────────────────────────────────────────────────────────────────────────────┤
│ Filter variants (exactly one active)                 EndpointLoggingFilter       │
│   RequestLoggingWebFilter (Reactor)   CoRequestLoggingWebFilter (coroutines)     │
├──────────────────────────────────────────────────────────────────────────────────┤
│ Shared choreography                                                              │
│   ExchangeLifecycle  ──▶  Exchange / ExchangeState                               │
│                      ──▶  ExchangeLogEmitter  ──▶  EndpointLogField              │
│                      ──▶  EndpointLoggingMetrics                                 │
├──────────────────────────────────────────────────────────────────────────────────┤
│ Capture                                                                          │
│   CapturingRequestDecorator · CapturingResponseDecorator · BoundedBodyCapture    │
├──────────────────────────────────────────────────────────────────────────────────┤
│ Cross-cutting                                                                    │
│   MdcKeys · TraceMdcKeys · MdcScope · EndpointMdcContextPropagation              │
│   Traceparent · NanoTimeSource · CorrelationIdGenerator · reportQuietly          │
└──────────────────────────────────────────────────────────────────────────────────┘
```

| Class | Responsibility |
|---|---|
| `RequestLoggingAutoConfiguration` | Registers the Reactor variant, the default `NanoTimeSource` and `CorrelationIdGenerator`, and — when `io.micrometer:context-propagation` is on the classpath — the MDC `ThreadLocalAccessor`s plus the propagation-mode warning. |
| `CoRequestLoggingAutoConfiguration` | Registers the coroutine variant when `kotlinx-coroutines-reactor` and `kotlinx-coroutines-slf4j` are present; ordered **before** the Reactor configuration so it claims the filter slot first. |
| `RequestLoggingProperties` | The `endpoint-logging.*` binding, validated in `init`. `HeaderLogProperties` (shared, limesium-common - §6.9) is one header section; `Variant` the reactive-only selector. |
| `EndpointLoggingFilter` | Marker contract (`WebFilter + Ordered`) both variants implement; the `@ConditionalOnMissingBean` target that guarantees exactly one filter. |
| `RequestLoggingWebFilter` | The **reference variant**: wires, runs the chain inside `Mono.defer`, maps `doOnError` / `doOnCancel` / `doFinally` to the lifecycle, and writes the identity into the Reactor context. |
| `CoRequestLoggingWebFilter` | The coroutine variant (`CoWebFilter`): same lifecycle, chain invoked inside `withContext(MDCContext(...))`, signals mapped via `try`/`catch`. |
| `ExchangeLifecycle` | Everything that decides **what** is logged and counted: activation matching, fail-open wiring, arrival line, the commit callback, guarded terminal handling, the exactly-once `complete`. |
| `Exchange` / `ExchangeState` | Per-exchange state between entry and emission; one atomic `OPEN → AWAITING_COMMIT → COMPLETED` state instead of loose flags. |
| `ExchangeLogEmitter` | Builds and emits the arrival line and the completion event; resolves level and outcome; records body sizes; opens the emission `MdcScope`. |
| `EndpointLogField` | The wire names and the exact JVM type of each structured field; a wrongly typed value drops the field with a warning, never the event. |
| `EndpointLoggingMetrics` | The six meters - the fixed-tag meters pre-registered, the body meters created lazily per tag - with per-meter fallback to a private registry on registration conflict. |
| `CapturingRequestDecorator` / `CapturingResponseDecorator` | The `DataBuffer` map-tee around request body reads and response body writes. |
| `BoundedBodyCapture` | The lock-guarded, freezable capture target; count-only mode with limit `0`; the request-side read state (`BodyReadState`). |
| `MdcScope` | Puts identity and trace keys into the MDC for the duration of one emission and restores the previous values. |
| `EndpointMdcContextPropagation` | `ThreadLocalAccessor`s bridging the Reactor context keys into the MDC; idempotent registration; startup warning. |
| `Traceparent` | Strict W3C `traceparent` parsing to `(traceId, parentSpanId)`. |
| `NanoTimeSource` / `CorrelationIdGenerator` / `HeaderValueMasker` | Injectable time, id and header masking; `SYSTEM` and the two `DEFAULT`s are the production defaults. |
| `reportQuietly` | Guards the diagnostics channel (counter + internal log) of every catch block. |

### 2.2 Auto-configuration and variant selection

Both auto-configurations are listed in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and share three
conditions:

- `@ConditionalOnWebApplication(type = REACTIVE)` — the module never activates in a servlet
  application, so it cannot clash with the servlet twin even if both jars are present;
- `@ConditionalOnProperty("endpoint-logging.enabled", matchIfMissing = true)` — the master switch;
- `@EnableConfigurationProperties(RequestLoggingProperties::class)`.

Variant selection works by **bean-slot claiming**:

```
CoRequestLoggingAutoConfiguration          RequestLoggingAutoConfiguration
  @AutoConfiguration(before = [Reactor])     (runs second)
  @ConditionalOnClass(CoWebFilter, MDCContext)
  @Conditional(NotForcedToReactor)
  @Bean @ConditionalOnMissingBean(EndpointLoggingFilter)   @Bean @ConditionalOnMissingBean(EndpointLoggingFilter)
    CoRequestLoggingWebFilter                                RequestLoggingWebFilter
                                                               check(variant != COROUTINE) { … fail … }
```

1. The coroutine configuration runs first. If `kotlinx-coroutines-reactor` **and**
   `kotlinx-coroutines-slf4j` are on the classpath and `endpoint-logging.variant` is not `reactor`, it
   registers `CoRequestLoggingWebFilter`.
2. The Reactor configuration then finds an `EndpointLoggingFilter` bean and backs off. Without one it
   registers `RequestLoggingWebFilter` — unless `variant=coroutine` was demanded, in which case the
   context start fails with a message naming the missing libraries.
3. A host-defined bean of either variant satisfies `@ConditionalOnMissingBean` and backs **both** off.

Result: exactly one `EndpointLoggingFilter` per application, ordered at
`Ordered.HIGHEST_PRECEDENCE + 10` so that the traceless correlation echo is set before anything else
runs.

The `NanoTimeSource` and `CorrelationIdGenerator` defaults are defined only in the Reactor
configuration but consumed by both variants — bean creation is independent of registration order.

### 2.3 Lifecycle of one exchange

The UML activity diagram [`activity-diagram.svg`](activity-diagram.svg) shows the complete flow of one
exchange — activation, wiring, the three terminal signals, the commit-deferred error path and the
exactly-once emission. The following sketch is the Reactor variant; the coroutine variant performs the identical steps with
`try`/`catch` instead of Reactor signals.

```
client ──▶ Netty ──▶ RequestLoggingWebFilter.filter(exchange, chain)
                       │
                       ├─ shouldNotFilter(path)?  ──yes──▶ chain.filter(exchange)   (untouched pass-through)
                       │
                       ├─ wireOrNull(exchange)    ──null─▶ chain.filter(exchange)   (fail-open, stage=wiring)
                       │     • request id: traceparent trace id, else header or generated (ADR-0002);
                       │       echoed on the response only when traceless
                       │     • body captures created if logging OR measuring is on
                       │     • exchange mutated with capturing decorators (only if a capture exists)
                       │     • traceparent parsed
                       │     • request headers selected and masked
                       │     • startNanos read from NanoTimeSource
                       │     • gauge exchanges.open += 1
                       │
                       ├─ logRequestStartIfEnabled(ex)        (optional arrival line, INFO)
                       │
                       └─ Mono.defer { chain.filter(mutatedExchange) }
                            .doOnError   { ex.failure = it }
                            .doOnCancel  { ex.cancelled = true }
                            .doFinally   { lifecycle.onTerminal(exchange, ex, COMPLETE|ERROR|CANCEL) }
                            .contextWrite { endpoint_request_id, endpoint_method, endpoint_route }
```

`onTerminal` then:

1. reads the best-matching handler pattern WebFlux recorded under
   `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` (the low-cardinality `endpoint_url_template`);
2. on `ERROR`: logs the immediate WARN breadcrumb on the module's own logger, and — if the response is
   not yet committed — registers the commit callback and moves the state to `AWAITING_COMMIT`
   ([§2.4](#24-emission-point-terminal-signal-commit-deferred-on-error));
3. otherwise calls `complete(ex)`.

`complete` is the **exactly-once** gate: a `getAndSet(COMPLETED)` on `Exchange.state` decides which of
the terminal callback or the commit callback wins; the winner decrements the gauge and calls
`ExchangeLogEmitter.logExchange`. The emitter freezes the body captures, computes duration, status,
outcome and level, records body sizes, gates on the logger level, opens the `MdcScope`, and writes one
event.

### 2.4 Emission point: terminal signal, commit-deferred on error

In WebFlux the `ON_ERROR` signal passes the filter **before** Spring's `ExceptionHandlingWebHandler` and
Boot's error renderer turn the exception into a 500 response. Emitting at that moment would log the
pre-rendering status — typically `200` — for a crashed exchange. The servlet twin solved the same
problem by emitting at `requestDestroyed`; this module defers differently:

| Terminal signal | Response state | Emission |
|---|---|---|
| `COMPLETE` | any | immediately at `doFinally` |
| `CANCEL` | any | immediately at `doFinally`; status may be absent ([§6.2](#62-cancellation-and-the-missing-status)) |
| `ERROR` | already committed | immediately at `doFinally` — the status is final |
| `ERROR` | not committed | **deferred** to `response.beforeCommit`, which sees the rendered status |

The commit callback is registered **at the error signal**, not at filter entry: Spring runs
`beforeCommit` actions in registration order, so registering late puts this callback behind every
action the chain itself registered (security header writers, session handling, a status mutation) and
lets it observe their effects.

Two residuals follow from the `beforeCommit` boundary and are documented rather than worked around:

- A **commit that never happens** — the connection died during error rendering, or an earlier commit
  action failed — leaves the exchange in `AWAITING_COMMIT`. No event is logged with a guessed status;
  instead the exchange stays **open on the gauge** `endpoint.logging.exchanges.open`, which is the
  module's liveness signal ([§5.5](#55-reading-the-meters-together)).
- A race between the error signal and a concurrently starting commit is handled: if
  `isCommitted` flips to `true` right after the callback was armed, the terminal side completes itself
  and the emitter's fallback to `response.statusCode` reads the rendered value.

### 2.5 The body tee

Bodies are never pre-read, buffered or replayed. The module installs a **passive map-tee**:

- `CapturingRequestDecorator.getBody()` wraps the body `Flux` in `map { tee(capture, it) }` — but only
  for the **first subscription**; a later subscription (a replaying request, a caching filter) passes
  through untouched so the logical body is counted once.
- `CapturingResponseDecorator.writeWith` / `writeAndFlushWith` do the same on the write side. A `Mono`
  body stays a `Mono` so Spring's single-buffer fast path in `AbstractServerHttpResponse` is preserved.
- `tee` reads at most `capture.remainingCapacity()` bytes out of each `DataBuffer` with a
  **non-advancing** read (the read position is untouched), counts the full length, and returns the
  original buffer. Ownership, pooling and release are exactly those of an undecorated exchange.

`BoundedBodyCapture` is the target: a `ByteArrayOutputStream` of at most `max-body-bytes`, a total byte
counter, and a `frozen` flag — all under one uncontended `ReentrantLock` (no `synchronized`, per the
repository's virtual-thread rule). With limit `0` it runs in **count-only** mode for the body-size
meters: nothing is buffered, every byte is counted, `tee` copies nothing.

The capture exists only when a body is logged (in any mode — `on-failure` needs the bytes before the outcome is
known, [§4.3](#43-body-logging-and-body-measuring)) **or** measured; without either, the exchange is not
mutated at all and the chain receives the original `ServerWebExchange`.

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

### 2.6 MDC and the Reactor context

There is no chain-wide thread-local MDC in a reactive application: the event loop that runs the filter
is not the thread that runs the handler's operators. The module provides the `endpoint_*` identity in
three places:

| Place | Mechanism | Who sees it |
|---|---|---|
| Emission scope | `MdcScope` around the single `log()` call | structured encoders emitting MDC fields on the exchange line and the arrival line |
| Message | inline `[endpoint_request_id=…]` | plain-text appenders |
| Reactor context | `contextWrite` with `endpoint_request_id`, `endpoint_method`, `endpoint_route` | the `ThreadLocalAccessor`s of `EndpointMdcContextPropagation` — and through them, under automatic propagation, every log line inside the handler |

Handler-side MDC (the servlet twin's "chain-wide MDC" parity) is therefore an **opt-in with two
prerequisites** for the Reactor variant: `io.micrometer:context-propagation` on the classpath (which
registers the accessors) **and** `spring.reactor.context-propagation=auto` (which makes Boot call
`Hooks.enableAutomaticContextPropagation()`). Boot's default `limited` restores thread-locals only
around `tap`/`handle`, so a log statement inside an ordinary `map` would carry nothing; the
auto-configuration warns once at startup when the accessors are registered but the mode is not `auto`.

The coroutine variant gets the parity natively: `CoRequestLoggingWebFilter` runs the chain inside
`withContext(MDCContext(ambient + identity))`, `CoWebFilter` publishes that coroutine context to the
handler, and `MDCContext` restores the map on every resumption. The installed map is an **additive
overlay** over the ambient MDC — trace ids, baggage and host keys survive, `endpoint_*` wins on
collision.

Both the accessor registration and the propagation-mode warning are installed only while the Reactor
variant owns the filter slot; with the coroutine variant or a host filter of another type they would be
false noise.

### 2.7 Fail-open contract

A logging component must never fail the request it describes. The module enforces that at every
boundary where it calls host-provided code (MDC adapter, appenders, `MeterRegistry`, a response facade):

| Stage | Where | What happens on failure | Counted as |
|---|---|---|---|
| wiring | `ExchangeLifecycle.wireOrNull` | the filter degrades to a plain pass-through for this request | `failopen{stage=wiring}` |
| wiring | commit-callback registration | the error path does not defer; the event completes at the terminal signal with the then-readable status | `failopen{stage=wiring}` |
| wiring | terminal bookkeeping (template read, breadcrumb) | confined; the exchange is still completed unless the deferral was armed | `failopen{stage=wiring}` |
| wiring | body-size recording, operational counter updates | the event follows without the sample / the count | `failopen{stage=wiring}` |
| wiring | ambient MDC snapshot (coroutine variant) | the chain runs without handler MDC | `failopen{stage=wiring}` |
| arrival | `ExchangeLogEmitter.logRequestStart` | the arrival line is dropped | `failopen{stage=arrival}` |
| emission | `ExchangeLogEmitter.logExchange`, commit-callback body | the exchange event is **lost** | `failopen{stage=emission}` |
| registration | `EndpointLoggingMetrics.registerOrFallback` | the conflicting meter lives in a private registry, warned once per name | — |

Every catch block reports through `reportQuietly`, which swallows a failure of the diagnostics channel
itself (a throwing `Counter`, a throwing appender that also covers the internal logger) — there is
nothing left to report to. `InterruptedException` is caught separately and the interrupt flag is
restored before the failure is recorded.

Failures of the logging are reported on the module's **own** loggers
(`eu.inqudium.limesium.reactive.logging.RequestLoggingWebFilter`, `…ExchangeLogEmitter`,
`…EndpointLoggingMetrics`), never on the exchange logger, so the exchange stream stays parseable.

**Security note.** Fail-open is the inverse of what an audit log needs: a host-side fault silently
removes the exchange from the log instead of failing the request. The exchange log is therefore an
**observability** feature with no completeness guarantee; a regulatory audit trail must come from a
fail-closed component. The compensating controls are `endpoint.logging.failopen` and the
`exchanges.open` gauge ([§5.5](#55-reading-the-meters-together)) — alert on them.

### 2.8 Injectable collaborators

Time and randomness are injected, not ambient:

- `NanoTimeSource` — monotonic nanoseconds for `endpoint_duration_ms` and the slow threshold; the
  single production read of `System.nanoTime()` is `NanoTimeSource.SYSTEM`. Log timestamps come from
  the logging backend, keeping the two time domains separate.
- `CorrelationIdGenerator` — the id for traceless requests without a correlation header;
  `CorrelationIdGenerator.DEFAULT` (a counting generator: random per-instance base-36 prefix plus
  counter, 21 characters) by default. Never consulted for a traced exchange (ADR-0002:
  the `traceparent` trace id is the request id).

- `HeaderValueMasker` — how a header listed in a `masked` section renders on the line; `DEFAULT` is the
  stable `length:hash` fingerprint ([§6.8](#68-masking-is-a-fingerprint-not-a-secret)).
  The properties decide WHICH values are masked, the bean decides HOW - a keyed HMAC for a compliance
  regime, a fixed `***` for a host that wants no correlation at all.

All three are `fun interface`s, all three are `@ConditionalOnMissingBean` beans, and all are what the
module's tests drive from an `AtomicLong` / a fixed string / a lambda without any mocking library.

---

## 3. Using it in a foreign project

### 3.1 Prerequisites

| Requirement | Notes |
|---|---|
| Spring Boot 4.x reactive web application | `@ConditionalOnWebApplication(type = REACTIVE)`; the module is inert in a servlet application |
| Java 21, Kotlin stdlib on the runtime classpath | the module is written in Kotlin; a Java host only needs `kotlin-stdlib`, which the jar pulls transitively |
| SLF4J 2.x binding (Logback by default in Boot) | the module uses the fluent `LoggingEventBuilder` API (`addKeyValue`) |
| Micrometer core | present via `spring-boot-starter-webflux`; an actuator `MeterRegistry` is optional |

The module is a **library**, not a starter: it declares `spring-boot-autoconfigure`, `slf4j-api`,
`spring-web`, `reactor-core`, `micrometer-core` and `kotlin-stdlib`, and nothing else — no logging
backend, no YAML, no Netty are forced onto the host.

### 3.2 Adding the dependency

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>limesium-reactive-logging</artifactId>
    <version><!-- current release: see the badge below --></version>
</dependency>
```

The current release is shown live by the Maven Central badge:
[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/limesium-reactive-logging.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/eu.inqudium/limesium-reactive-logging)

That is all: the auto-configuration registers the filter, every exchange is logged on the
`http-exchange` logger at INFO, the request id comes from the `traceparent` trace id (traceless
exchanges read/echo `X-Correlation-Id` instead — ADR-0002), and the
six meters are registered in the host's `MeterRegistry` if one exists.

To remove the module again without touching the classpath:

```yaml
endpoint-logging:
  enabled: false
```

### 3.3 Choosing the filter variant

| Host | Recommended | How |
|---|---|---|
| Reactor-only (`Mono`/`Flux` handlers, Java or Kotlin) | Reactor variant | default when the coroutine libraries are absent; if they arrive transitively, pin `endpoint-logging.variant: reactor` |
| Kotlin coroutines (`suspend fun` handlers, `Flow`) | coroutine variant | add the two optional libraries — their presence is the opt-in |

```xml
<!-- opt-in to the coroutine variant -->
<dependency>
    <groupId>org.jetbrains.kotlinx</groupId>
    <artifactId>kotlinx-coroutines-reactor</artifactId>
</dependency>
<dependency>
    <groupId>org.jetbrains.kotlinx</groupId>
    <artifactId>kotlinx-coroutines-slf4j</artifactId>
</dependency>
```

To make the choice explicit and fail loudly if the libraries go missing:

```yaml
endpoint-logging:
  variant: coroutine   # auto (default) | reactor | coroutine
```

Logging, configuration and metrics are identical across the variants by construction — both delegate
to the same `ExchangeLifecycle`. The only observable difference is how handler-side MDC is achieved
([§3.4](#34-enabling-handler-side-mdc)).

### 3.4 Enabling handler-side MDC

Goal: every log line written **inside** a handler carries `endpoint_request_id`, `endpoint_method` and
`endpoint_route`.

**Coroutine variant** — nothing to do. The chain runs inside `MDCContext`, and every resumption of the
handler coroutine restores the map.

**Reactor variant** — two steps:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>context-propagation</artifactId>
</dependency>
```

```yaml
spring:
  reactor:
    context-propagation: auto
```

Without the second step the auto-configuration logs, once at startup:

```
endpoint-logging registered the endpoint_* MDC accessors, but spring.reactor.context-propagation=<unset, default 'limited'>
does not enable automatic context propagation - handler-side MDC will not be restored around ordinary Reactor operators. …
```

A host that calls `Hooks.enableAutomaticContextPropagation()` itself can ignore the warning. Note that
`auto` applies to the whole application — it also restores any other registered `ThreadLocalAccessor`
(tracing, baggage) around every operator, with the corresponding per-operator cost.

### 3.5 Overriding beans

Every default is `@ConditionalOnMissingBean`:

```kotlin
@Configuration(proxyBeanMethods = false)
class EndpointLoggingCustomisation {

    /** Deterministic ids in a test profile, or a different id format. */
    @Bean
    fun correlationIdGenerator(): CorrelationIdGenerator =
        CorrelationIdGenerator { "req-" + ULID.random() }

    /** A keyed fingerprint where an unkeyed hash is not acceptable; both twins mask with this one bean. */
    @Bean
    fun headerValueMasker(secrets: Secrets): HeaderValueMasker =
        HeaderValueMasker { value -> "hmac:" + secrets.hmacSha256Hex(value).take(16) }

    /** Only if the host owns a monotonic clock abstraction already. */
    @Bean
    fun nanoTimeSource(clock: MonotonicClock): NanoTimeSource =
        NanoTimeSource { clock.nanos() }
}
```

A host-defined `RequestLoggingWebFilter` or `CoRequestLoggingWebFilter` bean replaces the
auto-configured filter entirely (both auto-configurations back off). Both constructors take
`(RequestLoggingProperties, NanoTimeSource, CorrelationIdGenerator, MeterRegistry)` plus an optional trailing `HeaderValueMasker` (the built-in fingerprint when omitted), so a custom bean
can still be built from the bound properties:

```kotlin
@Bean
fun requestLoggingWebFilter(
    properties: RequestLoggingProperties,
    nanoTime: NanoTimeSource,
    ids: CorrelationIdGenerator,
    registry: MeterRegistry,
): RequestLoggingWebFilter = RequestLoggingWebFilter(properties, nanoTime, ids, registry)
```

Keep in mind the one-instance-per-registry limitation of the gauge ([§6.7](#67-one-metrics-instance-per-registry)).

### 3.6 Logging backend and structured output

The module emits through SLF4J's fluent API. Every exchange event carries its data in **two places**, and
an encoder treats them differently:

| Data | Carried as | Examples |
|---|---|---|
| The field family | SLF4J **key-value pairs** (`addKeyValue`) | `endpoint_outcome`, `endpoint_duration_ms`, `endpoint_url_path`, `endpoint_request_body` |
| The identity and trace context | **MDC** entries, set by the emission scope | `endpoint_request_id`, `endpoint_method`, `endpoint_route`, `traceId`, `parentSpanId` (from the caller's `traceparent`) |

A plain `%msg` pattern shows neither — only the message, which repeats the gist inline
(`… -> 200 [endpoint_request_id=…]`) precisely for that case. Logback offers three ways to render the
rest; which one fits depends on where the output goes.

#### Option 1 — `PatternLayout` with `%kvp` and `%mdc` (text, for terminals and files)

Logback ≥ 1.3 renders the key-value pairs with the `%kvp` conversion word and the MDC with `%mdc`
(all entries) or `%X{key}` (one entry):

```xml
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg %kvp{NONE} [%mdc]%n</pattern>
    </encoder>
</appender>
```

```
13:54:58.534 INFO  [reactor-http-epoll-2] http-exchange - Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=0f7c… traceId=4bf9… parentSpanId=00f0…] endpoint_outcome=success endpoint_duration_ms=17 endpoint_request_method=GET endpoint_url_path=/api/things/42 endpoint_url_template=/api/things/{id} endpoint_response_status_code=200 [endpoint_method=GET, endpoint_request_id=0f7c…, endpoint_route=/api/things/42, traceId=4bf9…, parentSpanId=00f0…]
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
    eu.inqudium.limesium.reactive.logging: WARN
```

Key-value pairs and MDC entries become **flat top-level fields**, and values keep their JVM type —
`endpoint_duration_ms` is a number, `endpoint_response_status_code` a number, which is what the
type assertion in `EndpointLogField` guarantees on the producing side:

```json
{"@timestamp":"2026-08-23T13:54:58.534Z","log.level":"INFO","message":"Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=0f7c…]","endpoint_outcome":"success","endpoint_duration_ms":17,"endpoint_request_method":"GET","endpoint_url_path":"/api/things/42","endpoint_url_template":"/api/things/{id}","endpoint_response_status_code":200,"endpoint_request_id":"0f7c…","endpoint_method":"GET","endpoint_route":"/api/things/42","ecs.version":"8.11"}
```

This is the shape the component template in [§3.7](#37-index-mapping-elk) is written for. The same encoder is
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

Whatever the option, keep the `eu.inqudium.limesium.reactive.logging` logger at WARN or lower: it carries the
WARN breadcrumb on a thrown chain and the module's own failure reports.

### 3.7 Index mapping (ELK)

The thirteen `endpoint_*` fields have a ready-made Elasticsearch component template in the servlet
repository-shared [`/docs/elk/`](../../docs/elk/README.md). Compose it into the data-stream
mapping **before** the first event arrives — an unmapped body or header field would be mapped
dynamically and become searchable, which the payload fields' `index: false` deliberately prevents.

The MDC-carried keys are intentionally not in that template: where they land in the document depends on
the host's encoder layout; map them where the encoder configuration lives.

### 3.8 Verifying the integration

1. Start the application and call any endpoint:

   ```bash
   curl -i -H 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01' http://localhost:8080/api/things/42
   ```

   Expect **no** `X-Correlation-Id` response header (the exchange is traced — ADR-0002) and one
   `http-exchange` line with `endpoint_request_id=4bf92f… traceId=4bf92f… parentSpanId=00f0…`.
   Without the `traceparent` header (`curl -i -H 'X-Correlation-Id: demo-1' …`), expect
   `X-Correlation-Id: demo-1` echoed on the response and `endpoint_request_id=demo-1` on the line.

2. Check the meters (with actuator):

   ```bash
   curl -s localhost:8080/actuator/metrics/endpoint.logging.events
   curl -s localhost:8080/actuator/metrics/endpoint.logging.exchanges.open
   ```

   `events` should equal the number of logged lines; `exchanges.open` should be `0` when idle.

3. Throw from a handler and confirm the line logs the rendered `500` with `endpoint_outcome=failure` at
   ERROR, preceded by the WARN breadcrumb on `eu.inqudium.limesium.reactive.logging.RequestLoggingWebFilter`.

4. If handler MDC is expected, log something inside a handler and confirm `endpoint_request_id` is on
   that line too.

---

## 4. Configuration

All properties live under `endpoint-logging.*`. The namespace is **identical** to the servlet twin's,
key for key and default for default — the only reactive-only addition is `variant`. The complete,
commented reference with every default is this module's
[`docs/endpoint-logging-reference.yml`](endpoint-logging-reference.yml);
`EndpointLoggingReferenceConfigTest` binds it — and the servlet twin's reference — against
`RequestLoggingProperties` and pins the key parity, so neither file can drift from the code or from its
twin.

### 4.1 Property reference

| Property | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch. `false` makes both auto-configurations back off — no filter, no beans, no accessors. A context-start decision, not a runtime toggle. |
| `variant` | `auto` \| `reactor` \| `coroutine` | `auto` | **Reactive-only.** `auto` = coroutine variant when `kotlinx-coroutines-reactor` + `kotlinx-coroutines-slf4j` are present, Reactor otherwise. `reactor` forces the Reactor variant. `coroutine` requires the libraries and fails startup without them. |
| `logger-name` | string | `http-exchange` | Logger of the arrival line and the exchange event. Its level is the runtime volume control ([§4.5](#45-logger-levels)). |
| `correlation-id-header` | string (RFC 9110 token) | `X-Correlation-Id` | Header the correlation id is read from on **traceless** exchanges (no conformant `traceparent` — ADR-0002); blank/absent means generated. Only such an exchange gets the echo, set once at filter entry. A traced exchange takes its request id from the `traceparent` trace id, ignores this header and echoes nothing. |
| `include-query-string` | boolean | `true` | Log the query string as its own field `endpoint_url_query` (never part of the path). Disable when query parameters may carry personal data. |
| `log-request-start` | boolean | `false` | Additionally log an arrival line before the chain runs, at INFO, with the same emission MDC. Carries no outcome/status/duration. |
| `include-path-patterns` | list of `PathPattern` | `[]` | Endpoints the filter is active for at all; empty = every endpoint. Parsed once at startup; an invalid pattern fails the context. |
| `exclude-path-prefixes` | list of strings | `[]` | Request-URI prefixes the filter skips entirely — no event, no correlation echo, no gauge movement. Prefix match against the decoded request path. An exclude always wins over an include. |
| `slow-request-threshold` | duration | `5s` | At/above this duration an INFO exchange escalates to WARN and is flagged `endpoint_slow: true`; the outcome stays `success`. Compared at full precision; must be ≥ 1 ms. |
| `request-headers.includes` / `.excludes` / `.masked` | lists of header names | `[]` | See [§4.2](#42-header-sections). |
| `response-headers.includes` / `.excludes` / `.masked` | lists of header names | `[]` | See [§4.2](#42-header-sections). |
| `log-request-body` | `never` \| `on-failure` \| `always` | `never` | Tee the request body into `endpoint_request_body`, up to `max-body-bytes` — on every line (`always`) or only when the outcome is not `success` or the status is a 4xx (`on-failure`, [§4.3](#43-body-logging-and-body-measuring)). |
| `log-response-body` | `never` \| `on-failure` \| `always` | `never` | Tee the response body into `endpoint_response_body`, up to `max-body-bytes` — on every line or only when the outcome is not `success` or the status is a 4xx. |
| `measure-request-body-size` | boolean | `false` | Record `endpoint.request.body.size`; independent of `log-request-body`. |
| `measure-response-body-size` | boolean | `false` | Record `endpoint.response.body.size`; independent of `log-response-body`. |
| `max-body-bytes` | int > 0 | `16384` | Capture limit per body. Bounds **memory**, not the exchange: bytes beyond it still flow; the logged value is truncated with a note of the total size. |
| `masking-key` | string | *(empty)* | Keys the masking fingerprint: empty keeps the unkeyed `length:hash`, any other value turns it into an HMAC-SHA256 under the key — same shape, same stability under the same key, guess-proof without it. A **secret**: supply it like one; the properties' `toString` redacts it. Ignored when a host pins its own `HeaderValueMasker` bean. |

### 4.2 Header sections

Each direction has one section with four lists; matching is case-insensitive throughout. The section
is **masked by default** (ADR-0005): whatever it logs is rendered as a fingerprint unless the name is
explicitly allowed in plaintext, so the debugging move `includes: ["*"]` costs readability, never
confidentiality.

| List | Semantics |
|---|---|
| `includes` | Names to log. **Empty logs nothing** (the safe default). The entry `*` logs every header the message carries, deduplicated case-insensitively. |
| `excludes` | Names removed from the included set — meaningful mainly with `*`. An exclude always wins. `*` is rejected here at binding time (an empty `includes` already logs nothing). |
| `masked` | Names whose **value** is replaced by what the `HeaderValueMasker` bean renders — by default a fingerprint `length:hex`, the character length plus the first 64 bits of the SHA-256 of the UTF-8 value, e.g. `18:930bbdc51b6aed5c` (a **pseudonym**, not anonymisation: equal values stay recognisable as equal; key it with `masking-key` to stop guess confirmation). **Default `["*"]`: every logged header is masked** (ADR-0005). Narrow it to names, or empty it to switch masking off — a visible decision. Masking affects only headers that are logged; listing a name here does not include it. |
| `unmasked` | Names that appear in **plaintext** although `masked` covers them — the explicit allowlist of harmless names (`Content-Type`, `Accept`, a correlation id). An unmasked name always wins over a masked one. `*` is rejected here: the plaintext set is a list of names by design; to log everything in plaintext, empty `masked` instead. |

Multi-valued headers are joined with `, `. The selected pairs are rendered into one display-only field
per direction as `[Name:"value", Name2:"value2"]`; nothing is emitted when the selection is empty or no
selected header is present.

Request headers are selected at **wiring time** (filter entry); response headers at **emission time**,
so they reflect what the chain and the error renderer set.

### 4.3 Body logging and body measuring

Per direction, a **mode** decides whether a body is logged and a **flag** decides whether its size is
measured — independent of each other:

| `log-*-body` | `measure-*-body-size` | Capture installed | Buffered | Effect |
|---|---|---|---|---|
| `never` | off | no | — | exchange not mutated, zero overhead |
| `always` | off | yes, limit `max-body-bytes` | up to the limit | field logged on every line; no size sample |
| `on-failure` | off | yes, limit `max-body-bytes` | up to the limit | field logged only when `endpoint_outcome` is not `success` or the status is a 4xx; no size sample |
| `never` | on | yes, limit `0` (count-only) | nothing | size sample recorded; no field |
| `always` / `on-failure` | on | yes, limit `max-body-bytes` | up to the limit | both |

**`on-failure` is the volume switch** ([ADR-0006](../../docs/adr/ADR-0006-bodies-logged-by-outcome.md)).
`always` means every body of every exchange; what is nearly always wanted is bodies for the exchanges that
went wrong — `failure`, `timeout`, and `cancelled` — which cuts the volume by orders of magnitude and hits exactly
the lines a body is wanted for. The emitter decides when the outcome is final. The request body flows
before the outcome is known, so `on-failure` tees it exactly like `always` does (bounded by
`max-body-bytes`) and discards it for a success: the capture is paid, the output is saved — and the output
is what burdens the log pipeline. The gate is wider than the outcome vocabulary ([§5.3](#53-levels-and-outcomes)) by one status
class: a `4xx` response keeps its `success` outcome — the application answered — but its bodies are logged in
`on-failure`, because the client's error is exactly what the body explains; a `5xx` is `failure` and logs as
well. A slow but healthy exchange stays `success` and logs no bodies.

Rules that hold for every combination:

- The tee is passive: bytes are counted and (up to the limit) copied as they flow; nothing is
  pre-read, replayed or withheld. Streaming behaviour is untouched.
- An **unread request body** flows nowhere and is logged as absent; the size sample is not recorded.
- Zero-byte bodies produce no field and no sample — the distribution describes bodies that exist.
- Truncation is **byte-bounded**, and the decoder leaves an incomplete trailing multi-byte sequence
  undecoded rather than rendering a replacement character: `…<prefix>... [truncated, 12345 bytes total]`.
- The charset is the one `Content-Type` declares, UTF-8 when absent or unparsable.
- `measure-*` records what actually flowed, **exact beyond** `max-body-bytes`.
- `measure-request-body-size` additionally records `endpoint.request.body.read` — whether the application
  consumed the body completely, partially, or not at all ([§5.4](#54-meters)).

### 4.4 Path activation

```
active(path) = (include-path-patterns is empty  OR  any pattern matches path)
               AND no exclude-path-prefix is a prefix of path
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
`/files/{id}.pdf`); `exclude-path-prefixes` is a prefix match. Both see the request target the way the
WebFlux router does — the **path within the application** (a configured base path is stripped first,
exactly as in the handler mapping), whose segments **decode for matching** and drop path parameters —
so `/api/**` matches `/app/api/things` under base path `/app`, `/%61pi/things` is included by
`/api/**`, `/api%2Fthings` is not (the router sees one segment and would not serve it), and
`/%61ctuator/health` is excluded by `/actuator/health`. The logged `endpoint_url_path` stays raw and
keeps the base path.

### 4.5 Logger levels

Severity and semantic are decoupled: the level only decides how loud — and whether — a line is
emitted; `endpoint_outcome` carries the disposition ([§5.3](#53-levels-and-outcomes)). The level of the
`logger-name` logger therefore acts as the runtime volume control:

| `http-exchange` level | Emitted |
|---|---|
| `INFO` | every exchange |
| `WARN` | failures (5xx or error signal), cancellations, slow exchanges |
| `ERROR` | only exchanges whose chain signalled an error |
| `OFF` | nothing — and no event is even assembled |

Level and outcome are resolved **before** the event is built, so a disabled level costs no assembly,
no header selection, and no body decoding. Metrics are recorded **before** the level gate and are
unaffected by it — except `endpoint.logging.events`, which by definition counts emitted events only.

### 4.6 Validation at startup

`RequestLoggingProperties.init` and `HeaderLogProperties.init` reject, with a message naming the
property:

- blank `logger-name` or `correlation-id-header`;
- a `correlation-id-header` that is not an RFC 9110 token (it is written to every response; a
  non-token would be rejected per request by a strict server adapter and silently turn the filter into
  an unlogged pass-through);
- `max-body-bytes` ≤ 0;
- a blank (whitespace-only) `masking-key` - empty means unkeyed, whitespace is a worthless secret;
- `slow-request-threshold` < 1 ms (the logged duration has millisecond resolution);
- blank entries in any list;
- `*` in an `excludes` or an `unmasked` list;
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
    eu.inqudium.limesium.reactive.logging: WARN
```

**Diagnostics profile** — headers with masked credentials, request bodies, arrival lines:

```yaml
endpoint-logging:
  log-request-start: true
  log-request-body: always
  max-body-bytes: 16384
  request-headers:
    includes: ["*"]
    excludes: [Cookie]
    unmasked: [Accept, Content-Type, X-Correlation-Id]   # everything else stays a fingerprint
  response-headers:
    includes: [Content-Type, Content-Length]
    unmasked: [Content-Type, Content-Length]
```

**Production profile with bodies** — bodies only for the exchanges that went wrong; the request body is
teed up to `max-body-bytes` per exchange and dropped on success:

```yaml
endpoint-logging:
  log-request-body: on-failure
  log-response-body: on-failure
  max-body-bytes: 4096
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

**Reactor host with handler MDC:**

```yaml
endpoint-logging:
  variant: reactor
spring:
  reactor:
    context-propagation: auto
```

---

## 5. Metrics and observation

### 5.1 Log fields

The structured fields of the completion event (the arrival line carries only the first four of the
"always" rows without outcome/duration/status). The index types are those of the shared component
template; `EndpointLogFieldTest` keeps this module's enum in lockstep with it.

| Field | Type | Index | doc_values | When present | Notes |
|---|---|---|---|---|---|
| `endpoint_outcome` | keyword | yes | on | always | `success` / `failure` / `cancelled` — the field dashboards split by; decoupled from the level |
| `endpoint_duration_ms` | long | yes | on | always | from the injected monotonic source; measured until the terminal signal or commit |
| `endpoint_request_method` | keyword | yes | on | always | |
| `endpoint_url_path` | keyword | yes | **off** | always | the **raw** request path as sent (percent-encoding intact, like the servlet twin's `requestURI`), ids and all — filter exactly, never group |
| `endpoint_response_status_code` | short | yes | on | when a status is known | absent for a cancellation that never committed ([§6.2](#62-cancellation-and-the-missing-status)) |
| `endpoint_url_template` | keyword | yes | on | when WebFlux recorded a handler pattern | the aggregation half of the path pair, e.g. `/api/things/{id}` |
| `endpoint_url_query` | keyword | yes | **off** | when the request had one and `include-query-string` is on | raw, as sent |
| `endpoint_slow` | boolean | yes | on | only when the threshold was reached | absence means fast |
| `endpoint_async` | boolean | yes | on | **never** in this module | servlet-stack semantics; the constant exists so both enums map the same template |
| `endpoint_request_headers` | keyword | **no** | off | when selected headers are present | display only, rendered `[Name:"value", …]` |
| `endpoint_response_headers` | keyword | **no** | off | when selected headers are present | display only |
| `endpoint_request_body` | keyword | **no** | off | when `log-request-body` admits the outcome and bytes flowed | display only, bounded |
| `endpoint_response_body` | keyword | **no** | off | when `log-response-body` admits the outcome and bytes flowed | display only, bounded |

Each field asserts the exact JVM type of its value (`EndpointLogField.format`): a wrongly typed value
drops **that field** with a warning on `eu.inqudium.limesium.reactive.logging.EndpointLogField`, never the
event.

The throwable of a failed chain is attached to the event as its cause (`setCause`), so a structured
encoder renders the stack trace alongside the fields.

### 5.2 MDC keys

Set by `MdcScope` around each emission, and — depending on the variant and propagation setup — visible
inside handlers ([§2.6](#26-mdc-and-the-reactor-context)):

| Key | Value | Scope |
|---|---|---|
| `endpoint_request_id` | the request id: the `traceparent` trace id, or the accepted/generated correlation id (ADR-0002) — always set | emission; Reactor context; handler MDC when enabled |
| `endpoint_method` | the HTTP method | same |
| `endpoint_route` | the request **path** (the template is not known at filter entry) | same |
| `traceId` | trace id from `traceparent` | emission only |
| `parentSpanId` | parent id from `traceparent` — the **caller's** span | emission only |

`MdcScope` restores the previous value of every key on close (event-loop threads are pooled; an outer
filter may own the same keys), rolls back a partial install if the adapter throws mid-put, and restores
best-effort on close with the first failure rethrown and later ones suppressed.

### 5.3 Levels and outcomes

Resolved in this order in `ExchangeLogEmitter`:

| Condition | Level | `endpoint_outcome` |
|---|---|---|
| the chain signalled an error (`failure != null`) | `ERROR` | `failure` |
| the subscription was cancelled | `WARN` | `cancelled` |
| status ≥ 500 without an error signal (the application handled it) | `WARN` | `failure` |
| otherwise | `INFO` | `success` |
| … and the duration reached `slow-request-threshold` | `INFO → WARN` | unchanged, plus `endpoint_slow: true` |

Slowness raises severity; it never turns a completed exchange into a failure.

### 5.4 Meters

Six meters, all **consumed** from the host's `MeterRegistry` (an `ObjectProvider`; without one a
private `SimpleMeterRegistry` absorbs the values). All fixed-tag meters are **pre-registered at
construction**, so a `rate()` alert sees the zero before the first occurrence. Rates, latencies and
status distributions are deliberately left to `http.server.requests` and the log fields.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `endpoint.logging.failopen` | counter | `stage` = `emission` \| `arrival` \| `wiring` | Logging failures the fail-open path swallowed. `emission`: an exchange event was **lost**. `arrival`: a start line was lost. `wiring`: bookkeeping failed (pass-through degradation, a lost sample or counter, an unarmed deferral) — the event usually still follows. A lost log line cannot report itself through the same pipeline; this counter is the independent channel. |
| `endpoint.logging.events` | counter | `outcome` = `success` \| `failure` \| `cancelled` | Exchange events actually **emitted** on the exchange logger — after the level gate, arrival lines excluded. The reconciliation ground truth against the log index. |
| `endpoint.logging.exchanges.open` | gauge | — | Exchanges between filter entry (wiring) and the exactly-once completion. Hovers near the active-request count in health. |
| `endpoint.logging.correlation.id` | counter | `source` = `trace` \| `header` \| `generated` | Origin of each exchange's request id (ADR-0002); the meter name predates the decision and stays stable. |
| `endpoint.request.body.read` | counter | `uri` = handler pattern, `UNKNOWN` without one; `state` = `unread` \| `partial` \| `complete` | How far the application **consumed** the request body, opt-in via `measure-request-body-size`. Recorded once per exchange whenever the measuring tee exists — including bodyless requests the application never touched, which is the `unread` share the counter exists to show. `partial` = a subscription exists but no completion signal was observed (a cancelled subscription such as `take`, a client disconnect, an error mid-stream). Created lazily per `uri`/`state` on first use, like the size summaries. |
| `endpoint.request.body.size` / `endpoint.response.body.size` | distribution summary, base unit `bytes` | `uri` = handler pattern, `UNKNOWN` without one | Bytes that **actually flowed**, opt-in via `measure-*-body-size`, independent of body logging and level. Exact beyond `max-body-bytes`. Zero-byte bodies record no sample. Created lazily per `uri` on first use. |

**Registration conflicts.** Micrometer rejects a registration whose id already exists with a different
meter type. Rather than aborting the context (at construction) or suppressing an exchange event (at the
lazy body-size registration), the conflicting meter falls back to a private registry, warned once per
meter name on `eu.inqudium.limesium.reactive.logging.EndpointLoggingMetrics`: the module keeps working and that
meter is simply not exported.

### 5.5 Reading the meters together

The meters are designed to cover each other's blind spots:

| Question | Signal |
|---|---|
| Are exchange events being lost **loudly** (something threw)? | `failopen{stage=emission}` > 0 |
| Are exchange events being lost **silently** (nothing threw, terminal signal never arrived, commit never happened)? | `exchanges.open` baseline grows monotonically instead of returning towards 0 |
| Is the **log pipeline** (appender, broker, index) losing events? | `sum(endpoint.logging.events)` over a window ≠ count of indexed `http-exchange` documents for the same window |
| Did the upstream stop propagating identity (traceparent or correlation ids)? | the `generated` share of `correlation.id` rises |
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

Note on the gauge: an exchange deferred to a commit that never happens is **intended** to stay open —
that is the liveness signal, not a leak to suppress ([§2.4](#24-emission-point-terminal-signal-commit-deferred-on-error)).

### 5.6 Trace correlation

The event-loop thread that runs the filter carries no tracing-bridge MDC at filter time, so the module
reads the **incoming W3C `traceparent` header** instead:

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
                 └──────── traceId ───────────────┘ └─ parentSpanId ─┘
```

- `traceId` is the trace the server span runs under, published under Boot's logging-correlation key
  `traceId`, so the log-to-trace join holds.
- The header's parent-id is the **caller's** span. It is published as `parentSpanId` — never as
  `spanId`, where it would read as the local span and, with a bridge active, overwrite the real one.
- Parsing follows the W3C Trace Context Recommendation strictly: lowercase hex of fixed length, no
  all-zero ids, version `ff` forbidden, version `00` exactly four fields, higher versions parsed by the
  version-00 rules for their first four fields. A non-conformant header is ignored — nothing is logged,
  and the exchange counts as traceless for the identity decision.
- The conformance is pinned by `traceparent/conformance.txt`.
- Since ADR-0002 the trace id also **is** the exchange's `endpoint_request_id`, and a traced exchange
  gets no `X-Correlation-Id` echo — the identity decision and the trace fields share the one strict
  parse.

Inside handlers, with a Micrometer tracing bridge active, the local `spanId` is the bridge's — the
module never touches that key.

---

## 6. Special characteristics

### 6.1 Differences to the servlet twin

Everything not listed here behaves exactly as in `limesium-servlet-logging`.

| Concern | Servlet twin | This module |
|---|---|---|
| Disposition vocabulary | `success` / `failure` / `timeout` | `success` / `failure` / **`cancelled`** — there is no container async timeout in WebFlux; the reactive reality is a cancelled subscription (client disconnect) |
| `endpoint_async` | emitted | **never emitted** — everything is asynchronous, the flag would carry no information |
| Emission point | `requestDestroyed`, after the error dispatch | terminal signal; on an error with an uncommitted response deferred to the `beforeCommit` callback |
| Never-completing error rendering | n/a — destruction always fires | exchange stays **open on the gauge** instead of logging a wrong status |
| Chain-wide MDC | thread-local, for the whole chain | Reactor context + opt-in accessors (Reactor variant) or `MDCContext` (coroutine variant) |
| Trace context | parsed from `traceparent` at filter entry by the shared `Traceparent` (`traceId`/`parentSpanId`, ADR-0002), restored by the emission scope around the destruction callback — a pooled thread without per-request state | the same parsing and the same keys; restored by the emission scope at the terminal signal ([§5.6](#56-trace-correlation)) |
| Body tee | stream/writer wrappers; `reset()` clears the capture | `DataBuffer` map-tee; no reset analog — emitted buffers are on their way to the client |
| Variant selection | one filter | `endpoint-logging.variant` (`auto`/`reactor`/`coroutine`) |
| Handler template attribute | Spring MVC's `BEST_MATCHING_PATTERN_ATTRIBUTE` | WebFlux's `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` (mirrored as a constant so the module does not depend on `spring-webflux`; pinned by `HandlerMappingAttributeTest`) |

### 6.2 Cancellation and the missing status

A client disconnect cancels the subscription; `doFinally` fires with `CANCEL`, the exchange is emitted
immediately at WARN with `endpoint_outcome=cancelled`. If the response never committed, no status is
known: the message shows `-> -`, and `endpoint_response_status_code` is **omitted** rather than invented.
Dashboards must treat `endpoint_outcome` as the authoritative disposition and not assume the status
field is always present.

### 6.3 Error rendering bypasses the response tee

The response decorator sees only what is written through the **mutated** exchange the filter passes
down the chain. An **unhandled** error travels up to Spring's `WebExceptionHandler`s, and Boot's error
renderer writes the 500 body through the **original** response — those bytes bypass the tee. The event
still carries the rendered **status** (the commit callback observes the shared delegate), but
`endpoint_response_body` and the response-size sample stay absent for globally rendered error responses.
Responses rendered locally — a controller's `ResponseEntity`, a `@ControllerAdvice` — traverse the tee
normally. Pinned by the error-path integration test.

### 6.4 Zero-copy responses

`CapturingResponseDecorator` deliberately does **not** implement `ZeroCopyHttpOutputMessage`. Writers
check the response instance for that interface; wrapping makes file-serving handlers fall back to the
buffered path, so the bytes flow **through** the tee and are captured correctly — at the price of losing
the zero-copy optimisation while body capture or measuring is enabled. With both off, the exchange is not
decorated and zero-copy is untouched. Implementing the interface would silently re-open a capture bypass;
the mechanism is pinned by test.

### 6.5 Late body chunks after cancellation

Reactive Streams permits an already-requested `onNext` to arrive **after** a cancellation — on another
thread, after `doFinally` ran. The capture therefore does not rely on a single-writer assumption: every
mutation and read is under one lock, and the emitter's first step is `freeze()`. From then on a late tee
call is a no-op, so the logged body text and the size sample are one consistent snapshot instead of a
moving target.

### 6.6 Coroutine boundary and exception copies

In the coroutine variant the handler exception is rethrown after the terminal handling, so error
semantics stay with the upstream exception handler (whose rendered status the deferred emission waits
for). Across the coroutine-to-Reactor bridge, kotlinx's stack-trace recovery may surface a **copy** of
the exception with the original as its cause. Type, message and the reachable original — what error
handling classifies on — are preserved; only identity (`===`) is not. `CancellationException` is
rethrown as well: consuming it would break structured concurrency.

One residual of the coroutine MDC hand-off is deliberately not guarded: `MDCContext` installs and
restores the map inside kotlinx on every resumption, and an MDC adapter throwing **there** surfaces from
`withContext` indistinguishably from a handler failure. Such an adapter breaks every `MDCContext` user in
the host, not only this filter.

### 6.7 One metrics instance per registry

Micrometer deduplicates meters by id. A second `EndpointLoggingMetrics` instance against the same
registry shares the **counters** (increments merge) but not the **gauge**: the second gauge registration
is silently ignored and that instance's open-exchange movements become invisible. The auto-configuration
creates exactly one filter and therefore one instance; a host wiring additional filter instances against
one registry inherits this limitation knowingly.

### 6.8 Masking is a fingerprint, not a secret

By default `masked` replaces a header value with `length:sha256-prefix64` — stable, so a masked token
can still be correlated across events and modules (the servlet twin uses the same scheme, and so does
the outbound sibling Legatium), and a 64-bit cryptographic prefix makes accidental collisions
negligible. It is **unsalted and unkeyed**: it prevents plaintext exposure, not offline guessing. A
reader with a candidate list (usernames, tenant names, short API keys) can confirm a candidate by
hashing it. Do not treat the default as a security boundary for guessable values; omit such headers
from the selection instead — or **key** it: `endpoint-logging.masking-key` turns the fingerprint into an
HMAC-SHA256 under the key, same shape and stability, guess-proof without the key (a secret — supply it
as one). For any other shape the masker is the `HeaderValueMasker` bean (§2.8): a host pins its own (a
fixed `***` for no correlation at all) once, and both twins mask with it. The contract a replacement
must keep: never return the plaintext.

### 6.9 Shared code: limesium-common, inlined by Shade

The BYTE-identical part of the twins' shared layer lives in the `limesium-common` module
([ADR-0003](../../docs/adr/ADR-0003-limesium-common-inlined-by-shade.md)): the `Traceparent` parser
(with its tests and fuzz target), `HeaderLogProperties` (selection and masking fingerprint, with its
unit test and fuzz target - ADR-0003 amendment 2026-08-31), `NanoTimeSource`, `CorrelationIdGenerator`,
`reportQuietly`, and the MDC keys and scope. The Maven Shade plugin inlines those classes into THIS jar at package time, the
dependency-reduced POM drops the dependency, and `limesium-common` is never published — consumers keep
adding exactly one artifact, and the shared classes stay `internal` (`-Xfriend-paths`).

Everything whose twin copies genuinely differ stays deliberately duplicated, per the original
architecture-review decision: the field enum and metrics (per-stack outcome vocabulary and meter
descriptions), the emitters and exchanges, the properties (`variant` is reactive-only; the header
sections themselves are the shared `HeaderLogProperties`), and
`BoundedBodyCapture` (two different concurrency designs). For those the accepted cost is unchanged: a
change is a conscious port in both directions, and the lockstep tests catch *named* contract drift
(keys, field names, meter names, message text), not behavioural drift inside near-identical code.

---

## 7. Appendix

### 7.1 File map

```
limesium-reactive-logging/
├── pom.xml                                   library deps only; sibling docs as test resources
├── README.md                                 module summary and the twin-difference table
├── docs/
│   ├── GUIDE.md                              this document
│   ├── activity-diagram.svg                  UML activity diagram of one exchange
│   └── endpoint-logging-reference.yml        complete commented configuration reference (namespace + variant)
└── src/
    ├── main/kotlin/eu/inqudium/limesium/reactive/logging/
    │   ├── RequestLoggingAutoConfiguration.kt     Reactor variant, defaults, MDC accessors
    │   ├── CoRequestLoggingAutoConfiguration.kt   coroutine variant (before the Reactor one)
    │   ├── RequestLoggingProperties.kt            endpoint-logging.* binding, Variant (HeaderLogProperties: §6.9)
    │   ├── EndpointLoggingFilter.kt               WebFilter + Ordered marker
    │   ├── RequestLoggingWebFilter.kt             reference variant
    │   ├── CoRequestLoggingWebFilter.kt           coroutine variant
    │   ├── ExchangeLifecycle.kt                   shared choreography, TerminalKind
    │   ├── Exchange.kt                            per-exchange state, ExchangeState
    │   ├── ExchangeLogEmitter.kt                  arrival line and completion event
    │   ├── EndpointLogFields.kt                   field enum and builder helpers
    │   ├── EndpointLoggingMetrics.kt              the six meters
    │   ├── CapturingDecorators.kt                 request/response DataBuffer tee
    │   ├── BoundedBodyCapture.kt                  bounded, freezable capture target, BodyReadState
    │   └── EndpointMdcContextPropagation.kt       ThreadLocalAccessors and the propagation warning
    │   (Traceparent, Mdc, NanoTimeSource, CorrelationIdGenerator and reportQuietly live in
    │    ../limesium-common - inlined into this jar, §6.9)
    ├── main/resources/META-INF/spring/…AutoConfiguration.imports
    └── test/kotlin/eu/inqudium/limesium/reactive/logging/  see the suite overview below
```

Test-suite overview (the generated [test-evidence page](https://inqudium.github.io/limesium/tests/test-evidence/)
lists every test with its rationale):

| Suite | Scope |
|---|---|
| Unit suites (`RequestLoggingWebFilterTest`, `CoRequestLoggingWebFilterTest`, `…BodyAndHeaderTest`, `…MetricsTest`, `BoundedBodyCaptureTest`, `MdcContextPropagationTest`, `RequestLoggingAutoConfigurationTest`, …) | mock-exchange driven, deterministic; both filter variants against the shared lifecycle |
| `RequestLoggingWebFilterIntegrationTest` | end-to-end on real embedded **Netty** with the auto-selected (coroutine) variant: DataBuffer tee on pooled buffers, real WebFlux dispatch, commit-deferred error emission |
| `RequestLoggingWebFilterReactorIntegrationTest` | the **Reactor variant** on real Netty (coroutine auto-configuration excluded) — the majority consumer configuration without the optional coroutine libraries |
| `CoRequestLoggingWebFilterCoroutineIntegrationTest` | the **coroutine variant**'s `MDCContext` handler-MDC parity across real dispatcher hops |
| `RequestLoggingWebFilterTracingIntegrationTest` | ADR-0002 trace contract beside a real Brave bridge on Netty: header-parse join, identity decision, the documented no-`traceparent` boundary, the commit-deferred error path |
| Lockstep/contract tests (`TwinContractTest`, `EndpointLogFieldTest`, `EndpointLoggingReferenceConfigTest`, `HandlerMappingAttributeTest`) | pin the twin/wire/config contracts against the servlet twin and the shared reference YAML |

Fuzzing of the shared `Traceparent` parser and header masking lives in limesium-common; this module's engine matrix is a
single one (Netty) - WebFlux has no per-container WAR story, unlike the servlet twin's
Tomcat/Jetty/Undertow suites.

### 7.2 Related documents

- [`README.md`](../README.md) — module summary, the twin-difference table, the duplication decision.
- [`limesium-servlet-logging/README.md`](../../limesium-servlet-logging/README.md) — the reference
  implementation's documentation; everything not listed in [§6.1](#61-differences-to-the-servlet-twin)
  applies here unchanged.
- [`docs/endpoint-logging-reference.yml`](endpoint-logging-reference.yml) — this module's complete
  commented configuration reference (the shared namespace plus `variant`), bound together with the
  servlet twin's file by `EndpointLoggingReferenceConfigTest`.
- [`/docs/elk/README.md`](../../docs/elk/README.md) —
  the Elasticsearch component template for the `endpoint_*` fields.
