# limesium-reactive-logging — Guide

One structured `endpoint_*` log line per HTTP exchange in a Spring WebFlux application — with the same
message format, the same field family, the same `endpoint-logging.*` configuration and the same meters
as the servlet twin [`limesium-servlet-logging`](../../limesium-servlet-logging/README.md).

This guide holds what the **reactive stack decides**: the two filter variants and how one of them claims
the slot, the terminal signal with the commit-deferred error path as the emission point, the Reactor
context and handler-side MDC, the `DataBuffer` tee, and the reactive-only edge cases. Everything the two
modules share — the exchange line, the shared architecture, dependency and encoder setup, the whole
configuration namespace, the field family, the meters and the trace contract — is the
[common guide](../../docs/GUIDE.md) and is not repeated here. Both together are the long-form companion
to the module [README](../README.md). Everything here is derived from the code under
`src/main/kotlin/eu/inqudium/limesium/reactive/logging/`; when the two disagree, the code wins.

## Table of contents

1. [Introduction](#1-introduction)
   1. [What is specific to the reactive stack](#11-what-is-specific-to-the-reactive-stack)
   2. [What the module deliberately does not do](#12-what-the-module-deliberately-does-not-do)
2. [Architecture](#2-architecture)
   1. [Component overview](#21-component-overview)
   2. [Auto-configuration and variant selection](#22-auto-configuration-and-variant-selection)
   3. [Lifecycle of one exchange](#23-lifecycle-of-one-exchange)
   4. [Emission point: terminal signal, commit-deferred on error](#24-emission-point-terminal-signal-commit-deferred-on-error)
   5. [The body tee](#25-the-body-tee)
   6. [MDC and the Reactor context](#26-mdc-and-the-reactor-context)
   7. [Fail-open stages](#27-fail-open-stages)
3. [Using it in a foreign project](#3-using-it-in-a-foreign-project)
   1. [Prerequisites](#31-prerequisites)
   2. [Automatic wiring](#32-automatic-wiring)
   3. [Manual wiring](#33-manual-wiring)
   4. [Choosing the filter variant](#34-choosing-the-filter-variant)
   5. [Enabling handler-side MDC](#35-enabling-handler-side-mdc)
   6. [Replacing the filter bean](#36-replacing-the-filter-bean)
4. [Configuration on the reactive stack](#4-configuration-on-the-reactive-stack)
   1. [Property notes](#41-property-notes)
   2. [Header selection](#42-header-selection)
   3. [Body rules](#43-body-rules)
   4. [Path activation](#44-path-activation)
   5. [Logger levels](#45-logger-levels)
   6. [Example: Reactor host with handler MDC](#46-example-reactor-host-with-handler-mdc)
5. [Metrics and observation on the reactive stack](#5-metrics-and-observation-on-the-reactive-stack)
   1. [Log fields](#51-log-fields)
   2. [MDC keys](#52-mdc-keys)
   3. [Levels and outcomes](#53-levels-and-outcomes)
   4. [Meters](#54-meters)
   5. [Reading the meters together](#55-reading-the-meters-together)
   6. [Trace correlation](#56-trace-correlation)
6. [Special characteristics](#6-special-characteristics)
   1. [Cancellation and the missing status](#61-cancellation-and-the-missing-status)
   2. [Error rendering bypasses the response tee](#62-error-rendering-bypasses-the-response-tee)
   3. [Zero-copy responses](#63-zero-copy-responses)
   4. [Late body chunks after cancellation](#64-late-body-chunks-after-cancellation)
   5. [Coroutine boundary and exception copies](#65-coroutine-boundary-and-exception-copies)
   6. [Framework-parsed bodies bypass the tee](#66-framework-parsed-bodies-bypass-the-tee)
7. [Appendix](#7-appendix)
   1. [File map](#71-file-map)
   2. [Related documents](#72-related-documents)

---

## 1. Introduction

### 1.1 What is specific to the reactive stack

What every Limesium module does for an inbound exchange — identity per ADR-0002, the optional arrival
line, the duration, the body tee, the header selection, the `traceparent` parse, exactly one completion
event, six meters, all fail-open — is the [common guide's §1.1](../../docs/GUIDE.md#11-what-the-modules-do).
`limesium-reactive-logging` realises it as a Spring Boot auto-configured `WebFilter` for **reactive**
(WebFlux) applications, in two variants of which exactly one is active, and decides the things a
non-blocking stack must decide differently:

- the filter comes as a **Reactor variant** and a **coroutine variant**; the classpath or
  `endpoint-logging.variant` selects one, and both delegate to the same lifecycle
  ([§2.2](#22-auto-configuration-and-variant-selection), [§3.4](#34-choosing-the-filter-variant));
- the completion event is emitted at the **terminal signal** — and, for an error on an uncommitted
  response, deferred to the commit callback that sees the rendered status
  ([§2.4](#24-emission-point-terminal-signal-commit-deferred-on-error));
- a client disconnect is the `cancelled` disposition, and a never-committed cancellation logs no status
  at all ([§6.1](#61-cancellation-and-the-missing-status)); there is no `endpoint_async` field
  ([§5.1](#51-log-fields));
- the identity rides the **Reactor context**, and handler-side MDC is an opt-in through context
  propagation — or native in the coroutine variant ([§2.6](#26-mdc-and-the-reactor-context));
- the body tee is a `DataBuffer` map-tee in request/response decorators, with a lock-guarded, freezable
  capture ([§2.5](#25-the-body-tee)).

### 1.2 What the module deliberately does not do

Beyond the non-goals shared by both modules
([common guide §1.2](../../docs/GUIDE.md#12-what-the-modules-deliberately-do-not-do)), one is specific
to this stack:

- **No chain-wide thread-local MDC by itself.** Reactive handlers hop event-loop threads; handler-side
  MDC is an opt-in that needs either the coroutine variant or Micrometer's context propagation
  ([§2.6](#26-mdc-and-the-reactor-context)).

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

The emission, capture and cross-cutting components carry the same names and contracts in both modules —
which of them are one shared class and which are per-stack twins is the
[common guide's §2.1](../../docs/GUIDE.md#21-shared-components). The reactive-side responsibilities:

| Class | Responsibility |
|---|---|
| `RequestLoggingAutoConfiguration` | Registers the Reactor variant, the default `NanoTimeSource`, `CorrelationIdGenerator` and `HeaderValueMasker`, and — when `io.micrometer:context-propagation` is on the classpath — the MDC `ThreadLocalAccessor`s plus the propagation-mode warning. |
| `CoRequestLoggingAutoConfiguration` | Registers the coroutine variant when `kotlinx-coroutines-reactor` and `kotlinx-coroutines-slf4j` are present; ordered **before** the Reactor configuration so it claims the filter slot first. |
| `RequestLoggingProperties` | The `endpoint-logging.*` binding, validated in `init`. `HeaderLogProperties` (shared, limesium-common — [common guide §6.4](../../docs/GUIDE.md#64-shared-code-limesium-common-inlined-by-shade)) is one header section; `Variant` the reactive-only selector. |
| `EndpointLoggingFilter` | Marker contract (`WebFilter + Ordered`) both variants implement; the `@ConditionalOnMissingBean` target that guarantees exactly one filter. |
| `RequestLoggingWebFilter` | The **reference variant**: wires, runs the chain inside `Mono.defer`, maps `doOnError` / `doOnCancel` / `doFinally` to the lifecycle, and writes the identity into the Reactor context. |
| `CoRequestLoggingWebFilter` | The coroutine variant (`CoWebFilter`): same lifecycle, chain invoked inside `withContext(MDCContext(...))`, signals mapped via `try`/`catch`. |
| `ExchangeLifecycle` | Everything that decides **what** is logged and counted: activation matching, fail-open wiring, arrival line, the commit callback, guarded terminal handling, the exactly-once `complete`. |
| `Exchange` / `ExchangeState` | Per-exchange state between entry and emission; one atomic `OPEN → AWAITING_COMMIT → COMPLETED` state instead of loose flags. |
| `ExchangeLogEmitter` | Builds and emits the arrival line and the completion event; resolves level and outcome (cancellation included); records body sizes; opens the emission `MdcScope`. |
| `EndpointLogField` | The wire names and the exact JVM type of each structured field — this module's enum carries the `cancelled` outcome and never emits `endpoint_async`. |
| `EndpointLoggingMetrics` | The six meters, with `cancelled` in the `outcome` tag vocabulary. |
| `CapturingRequestDecorator` / `CapturingResponseDecorator` | The `DataBuffer` map-tee around request body reads and response body writes. |
| `BoundedBodyCapture` | The lock-guarded, freezable capture target; count-only mode with limit `0`; the request-side read state (`BodyReadState`). |
| `MdcScope` | Puts identity and trace keys into the MDC for the duration of one emission and restores the previous values. |
| `EndpointMdcContextPropagation` | `ThreadLocalAccessor`s bridging the Reactor context keys into the MDC; idempotent registration; startup warning. |

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

The `NanoTimeSource`, `CorrelationIdGenerator` and `HeaderValueMasker` defaults are defined only in the
Reactor configuration but consumed by both variants — bean creation is independent of registration
order.

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
| `CANCEL` | any | immediately at `doFinally`; status may be absent ([§6.1](#61-cancellation-and-the-missing-status)) |
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

When the chain signals an error, a short **WARN breadcrumb** is logged immediately on the module's own
logger (`eu.inqudium.limesium.reactive.logging.RequestLoggingWebFilter`) — the exception's `toString`, no
stack trace — so the failure is visible the moment it happens, while the full ERROR event with the cause
follows at the (possibly deferred) emission.

### 2.5 The body tee

The principle — a passive tee into a bounded capture that mirrors what the application consumed, never
what the client transmitted — is the
[common guide's §2.3](../../docs/GUIDE.md#23-the-body-tee-capture-mirrors-consumption). On this stack
the tee is a **map-tee** in two decorators:

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
meters: nothing is buffered, every byte is counted, `tee` copies nothing. Why it must be freezable is
[§6.4](#64-late-body-chunks-after-cancellation).

The capture exists only when a body is logged (in any mode) **or** measured; without either, the
exchange is not mutated at all and the chain receives the original `ServerWebExchange`. What flows
beside the decorators — Boot's error rendering — and what the decoration costs is
[§4.3](#43-body-rules).

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

### 2.7 Fail-open stages

The contract — no failure inside the logging may fail, delay or alter the request; every failure
counted by stage and reported on the module's own loggers; the security note on what fail-open means for
an audit trail — is the [common guide's §2.4](../../docs/GUIDE.md#24-fail-open-contract). The boundaries
on this stack:

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

The module's own loggers are `eu.inqudium.limesium.reactive.logging.RequestLoggingWebFilter`,
`…ExchangeLogEmitter` and `…EndpointLoggingMetrics`. One residual of the coroutine MDC hand-off is
deliberately not guarded ([§6.5](#65-coroutine-boundary-and-exception-copies)).

---

## 3. Using it in a foreign project

### 3.1 Prerequisites

The requirements shared by both modules — Spring Boot 4.x, Java 21 with the Kotlin stdlib, an SLF4J 2.x
binding, Micrometer core — are the [common guide's §3.1](../../docs/GUIDE.md#31-prerequisites); the
dependency snippet and the badge are its [§3.2](../../docs/GUIDE.md#32-adding-the-dependency). This
stack adds:

| Requirement | Notes |
|---|---|
| Spring Boot 4.x **reactive** web application | `@ConditionalOnWebApplication(type = REACTIVE)`; the module is inert in a servlet application. The server (Reactor Netty by default) comes with the host's WebFlux starter; the module forces none |
| `kotlinx-coroutines-reactor` + `kotlinx-coroutines-slf4j` | **optional** — their presence selects the coroutine filter variant ([§3.4](#34-choosing-the-filter-variant)) |
| `io.micrometer:context-propagation` | **optional** — its presence enables handler-side MDC for the Reactor variant ([§3.5](#35-enabling-handler-side-mdc)) |

The module is a **library**, not a starter: it declares `spring-boot-autoconfigure`, `slf4j-api`,
`spring-web`, `reactor-core`, `micrometer-core` and `kotlin-stdlib`, and nothing else — no logging
backend, no YAML, no Netty are forced onto the host. The two optional libraries change the wiring, never
the output.

### 3.2 Automatic wiring

On this stack the wiring **is** the bean. Both auto-configurations are listed in the auto-configuration
imports resource and conditional on a **reactive** web application (`@ConditionalOnWebApplication(type =
REACTIVE)` — in a servlet application the module is inert, and the servlet twin takes over) and on
`endpoint-logging.enabled` (default `true`; `false` removes the filter and the defaults together). They
register exactly **one `EndpointLoggingFilter` bean** — a `WebFilter` that is also `Ordered` — and
nothing else is needed:

1. Boot's WebFlux auto-configuration builds the application's `HttpHandler` through
   `WebHttpHandlerBuilder.applicationContext(context)`, which **collects every `WebFilter` bean** from
   the context.
2. The builder sorts them by their `Ordered` contract; this filter says
   `Ordered.HIGHEST_PRECEDENCE + 10`, early enough that the traceless correlation echo is set before
   anything else runs and everything after it sees the exchange identity.
3. Which class fills the slot is decided by **bean-slot claiming** ([§2.2](#22-auto-configuration-and-variant-selection)):
   with `kotlinx-coroutines-reactor` **and** `kotlinx-coroutines-slf4j` on the classpath the coroutine
   auto-configuration runs first and registers `CoRequestLoggingWebFilter`; otherwise the Reactor
   auto-configuration registers `RequestLoggingWebFilter`. `endpoint-logging.variant` overrides the
   classpath ([§3.4](#34-choosing-the-filter-variant)).

Consequently there is nothing for the host to inject and nothing to build: every exchange the server
hands to the `WebHandler` passes the filter — annotated controllers, router functions, static
resources, the error rendering — and **path activation** (`include-path-patterns`,
`exclude-path-prefixes`) is evaluated inside the filter, byte-identical with the servlet twin
([§4.4](#44-path-activation)).

```kotlin
@RestController
class ThingsController(private val things: Things) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/api/things/{id}")
    suspend fun thing(@PathVariable id: Long): Thing {
        log.info("loading thing")        // coroutine variant: carries endpoint_request_id, endpoint_method, endpoint_route
        return things.load(id)
    }
}
```

Handler-side MDC — the line above carrying the identity — is native in the coroutine variant and an
opt-in for the Reactor variant: with `io.micrometer:context-propagation` on the classpath the Reactor
auto-configuration registers the `endpoint_*` `ThreadLocalAccessor`s and validates
`spring.reactor.context-propagation=auto` at startup, **only while a `RequestLoggingWebFilter` owns the
slot** ([§3.5](#35-enabling-handler-side-mdc)). That initializer is part of the automatic wiring: it is
not reachable from a filter wired by hand.

Covered by the automatic wiring:

- every exchange of the application's `HttpHandler`, whatever ends it;
- a host-defined bean of **either** variant: it satisfies the missing-bean condition, both
  auto-configurations back off, and WebFlux collects the host's bean like any other
  ([§3.6](#36-replacing-the-filter-bean)).

**Not** covered — for these, [§3.3](#33-manual-wiring) applies:

- an `HttpHandler` assembled without Boot's WebFlux auto-configuration (`WebHttpHandlerBuilder` or
  `RouterFunctions.toHttpHandler(...)` called by the host);
- a router function or server outside a Spring context.

All of it is pinned by `RequestLoggingAutoConfigurationTest`: the registration in a reactive context,
the back-off when disabled, the variant selection in both directions, the host-bean back-off, the
accessor registration and its startup warning. The wiring is fail-open like everything else: a failure
while wiring one exchange degrades it to a pass-through with a `stage=wiring` count
([§2.7](#27-fail-open-stages)).

To confirm the attachment at runtime — in a test or a startup check — ask the context for its
`WebFilter` beans; exactly one `EndpointLoggingFilter` must be among them:

```kotlin
val filters = context.getBeansOfType(WebFilter::class.java).values
check(filters.count { it is EndpointLoggingFilter } == 1)
```

### 3.3 Manual wiring

The filter bean exists in every enabled reactive context; only its **pickup** depends on WebFlux
collecting `WebFilter` beans from a Boot application context. Add it yourself when the `HttpHandler` is
assembled without that context scan:

| Situation | Why the automatic wiring does not reach it |
|---|---|
| WebFlux assembled **without Boot's auto-configuration** — an `HttpHandler` the host builds through `WebHttpHandlerBuilder.webHandler(...)` or `RouterFunctions.toHttpHandler(router, strategies)` | those take exactly the filters they are given; a bean in the context is not consulted |
| A router function or server **outside a Spring context** — `WebTestClient.bindToRouterFunction(...)` in a test, a library's own server | there is no context to hold the bean, so the filter is constructed directly |
| A Boot context with the auto-configuration switched off (`endpoint-logging.enabled=false`) that still wants the filter | the host defines the bean itself; WebFlux collects it regardless of who defined it — and the host binds the properties class, because `@EnableConfigurationProperties` lives on the auto-configuration that is now gone |

There is **no "different order" case**: the order is a property of the filter itself (`getOrder()` on
both variants), not of a registration, so it cannot be changed by wiring differently.

The mechanics are one call: construct the variant of choice — `RequestLoggingWebFilter`, or
`CoRequestLoggingWebFilter` when the two coroutine libraries are present — and hand it to whatever
assembles the handler. The constructor takes the bound properties, the time source, the id generator
and a `MeterRegistry`, plus an optional trailing `HeaderValueMasker` (the built-in fingerprint when
omitted); every default is public:

```kotlin
val filter = RequestLoggingWebFilter(
    RequestLoggingProperties(),            // every default; or a copy(...) with the fields to change
    NanoTimeSource.SYSTEM,
    CorrelationIdGenerator.DEFAULT,
    SimpleMeterRegistry(),                 // or the registry the surrounding code owns
)

// a hand-assembled handler
val httpHandler = RouterFunctions.toHttpHandler(
    router,
    HandlerStrategies.builder().webFilter(filter).build(),
)

// a router function under test
val client = WebTestClient.bindToRouterFunction(router).webFilter<WebTestClient.RouterFunctionSpec>(filter).build()
```

Inside a Boot context with the auto-configuration switched off, the same construction is a bean:

```kotlin
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RequestLoggingProperties::class)
class EndpointLoggingConfiguration {
    @Bean
    fun requestLoggingWebFilter(properties: RequestLoggingProperties, registry: MeterRegistry): RequestLoggingWebFilter =
        RequestLoggingWebFilter(properties, NanoTimeSource.SYSTEM, CorrelationIdGenerator.DEFAULT, registry)
}
```

The rules that hold for a hand-wired filter on either stack — one filter per `MeterRegistry`,
activation is not the host's business, the overridable beans stay overridable, the host binds the
properties class — are the [common guide's §3.3](../../docs/GUIDE.md#33-wiring). Three are
reactive-specific:

- **The variant is the host's choice by class.** There is no `endpoint-logging.variant` evaluation
  outside the auto-configuration; `CoRequestLoggingWebFilter` needs `kotlinx-coroutines-reactor` and
  `kotlinx-coroutines-slf4j` on the classpath ([§3.4](#34-choosing-the-filter-variant)).
- **Handler-side MDC comes with the coroutine variant only.** The context-propagation accessors of the
  Reactor variant are installed by the auto-configuration's initializer, which a hand-wired filter
  does not have; a Reactor filter wired by hand carries the identity in the Reactor context, the
  emission-scope MDC and the message inline ([§2.6](#26-mdc-and-the-reactor-context)).
- **Ordering is the host's business.** Only the context scan sorts by `Ordered`; filters handed to
  `WebHttpHandlerBuilder.filter(...)` or `HandlerStrategies.Builder.webFilter(...)` run in the order
  added — put this one first, so the correlation echo is set before anything else runs.

Everything else is unchanged by the way the filter was added: emission point, outcomes, meters, header
sections, body capture and the fail-open contract behave exactly as under the automatic wiring — the
filter does not know how it got onto the chain.

### 3.4 Choosing the filter variant

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
([§3.5](#35-enabling-handler-side-mdc)).

### 3.5 Enabling handler-side MDC

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

### 3.6 Replacing the filter bean

The collaborator beans — `CorrelationIdGenerator`, `HeaderValueMasker`, `NanoTimeSource` — are
overridden the same way on both stacks
([common guide §3.4](../../docs/GUIDE.md#34-overriding-the-collaborator-beans)). The filter itself is
also `@ConditionalOnMissingBean`, and replacing it is where this stack differs: a host-defined
`RequestLoggingWebFilter` or `CoRequestLoggingWebFilter` bean replaces the auto-configured filter
entirely (both auto-configurations back off — and with them the context-propagation initializer, which
is installed only while an auto-configured `RequestLoggingWebFilter` owns the slot). Both constructors
take `(RequestLoggingProperties, NanoTimeSource, CorrelationIdGenerator, MeterRegistry)` plus an optional
trailing `HeaderValueMasker` (the built-in fingerprint when omitted), so a custom bean can still be
built from the bound properties:

```kotlin
@Bean
fun requestLoggingWebFilter(
    properties: RequestLoggingProperties,
    nanoTime: NanoTimeSource,
    ids: CorrelationIdGenerator,
    registry: MeterRegistry,
): RequestLoggingWebFilter = RequestLoggingWebFilter(properties, nanoTime, ids, registry)
```

Keep in mind the one-instance-per-registry limitation of the gauge
([common guide §6.2](../../docs/GUIDE.md#62-one-metrics-instance-per-registry)).

---

## 4. Configuration on the reactive stack

The namespace, every property with its default, the header sections, the body modes, the logger levels,
the startup validation and the example configurations are the
[common guide's §4](../../docs/GUIDE.md#4-configuration) — identical on both stacks by construction. The
complete reference for THIS module is [`docs/endpoint-logging-reference.yml`](endpoint-logging-reference.yml)
(the shared namespace plus `variant`); `EndpointLoggingReferenceConfigTest` binds it — and the servlet
twin's reference — against `RequestLoggingProperties` and pins the key parity, so neither file can drift
from the code or from its twin. This section lists what the reactive stack adds to the meaning of
individual properties.

### 4.1 Property notes

| Property | On this stack |
|---|---|
| `variant` | **Reactive-only.** `auto` (default) = coroutine variant when `kotlinx-coroutines-reactor` + `kotlinx-coroutines-slf4j` are present, Reactor otherwise; `reactor` forces the Reactor variant; `coroutine` requires the libraries and fails startup without them ([§3.4](#34-choosing-the-filter-variant)). |
| `enabled` | `false` makes both auto-configurations back off — no filter, no default beans, no context-propagation accessors. |
| `log-request-start` | The arrival line is logged with the same emission-scope MDC as the completion event — there is no chain scope to log it inside. |
| `include-path-patterns` / `exclude-path-prefixes` | Matched against the path within the application — the base path stripped, segments decoded ([§4.4](#44-path-activation)). |
| `slow-request-threshold` | Compared at full precision against the duration from filter entry to the terminal signal or the commit. |

### 4.2 Header selection

Multi-valued headers are joined with `, `. Request headers are selected at **wiring time** (filter
entry); response headers at **emission time** — the terminal signal, or the commit callback for a
deferred error — so they reflect what the chain and the error renderer set.

### 4.3 Body rules

In addition to the rules that hold on both stacks
([common guide §4.3](../../docs/GUIDE.md#43-body-logging-and-body-measuring)):

- Streaming behaviour is untouched: the tee is a non-advancing read out of each `DataBuffer`; the
  original buffer flows on ([§2.5](#25-the-body-tee)).
- The charset is the one `Content-Type` declares, UTF-8 when absent or unparsable.
- There is no reset analog: emitted buffers are on their way to the client, so nothing ever discards a
  capture.
- Boot's error renderer writes an unhandled error's 500 body through the original response and bypasses
  the tee ([§6.2](#62-error-rendering-bypasses-the-response-tee)).
- While body capture or measuring is enabled, file-serving handlers lose the zero-copy optimisation —
  the price of the bytes flowing through the tee ([§6.3](#63-zero-copy-responses)).
- A body chunk arriving after a cancellation is a no-op on the frozen capture
  ([§6.4](#64-late-body-chunks-after-cancellation)).
- A body **WebFlux parses itself** — a form POST read through `@ModelAttribute` or `getFormData()`, a
  multipart request through `getMultipartData()` — flows beside the tee: no `endpoint_request_body`,
  no size sample, read state `unread` ([§6.6](#66-framework-parsed-bodies-bypass-the-tee)).

### 4.4 Path activation

The activation rule and the pattern syntax are the [common guide's §4.4](../../docs/GUIDE.md#44-path-activation).
On this stack both lists see the request target the way the **WebFlux router** does — the **path within
the application** (a configured base path is stripped first, exactly as in the handler mapping), whose
segments **decode for matching** and drop path parameters — so `/api/**` matches `/app/api/things`
under base path `/app`, `/%61pi/things` is included by `/api/**`, `/api%2Fthings` is not (the router
sees one segment and would not serve it), and `/%61ctuator/health` is excluded by `/actuator/health`.
The logged `endpoint_url_path` stays raw and keeps the base path. An inactive request passes through
without any trace — no correlation echo, no event, no gauge movement, no counters.

### 4.5 Logger levels

The level/outcome decoupling and the cost model of a disabled level are the
[common guide's §4.5](../../docs/GUIDE.md#45-logger-levels). With this stack's dispositions:

| `endpoint-http-exchange` level | Emitted |
|---|---|
| `INFO` | every exchange |
| `WARN` | failures (5xx or error signal), cancellations, slow exchanges |
| `ERROR` | only exchanges whose chain signalled an error |
| `OFF` | nothing — and no event is even assembled |

### 4.6 Example: Reactor host with handler MDC

The example configurations of the [common guide's §4.7](../../docs/GUIDE.md#47-example-configurations)
apply unchanged (with `eu.inqudium.limesium.reactive.logging` as the module logger). One is
reactive-only — a Reactor host that pins the variant and enables handler-side MDC
([§3.5](#35-enabling-handler-side-mdc)):

```yaml
endpoint-logging:
  variant: reactor
spring:
  reactor:
    context-propagation: auto
```

---

## 5. Metrics and observation on the reactive stack

The field family with its index types, the MDC keys, the six meters, how to read them together with the
suggested alert set, and the trace contract are the
[common guide's §5](../../docs/GUIDE.md#5-metrics-and-observation). This section lists what the reactive
stack decides within them.

### 5.1 Log fields

| Field | On this stack |
|---|---|
| `endpoint_outcome` | `success` / `failure` / **`cancelled`** — a cancelled subscription, typically a client disconnect ([§5.3](#53-levels-and-outcomes)). |
| `endpoint_duration_ms` | Measured until the terminal signal or, for a deferred error, the commit. |
| `endpoint_response_status_code` | Present when a status is known; **absent** for a cancellation that never committed ([§6.1](#61-cancellation-and-the-missing-status)). Dashboards must treat `endpoint_outcome` as the authoritative disposition. |
| `endpoint_async` | **Never emitted** — everything is asynchronous here, the flag would carry no information; the enum keeps the constant so both modules map the same template. |
| `endpoint_url_template` | Present when WebFlux recorded a handler pattern under `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` (mirrored as a constant so the module does not depend on `spring-webflux`; pinned by `HandlerMappingAttributeTest`). |
| `endpoint_response_body` | Absent for globally rendered error responses ([§6.2](#62-error-rendering-bypasses-the-response-tee)). |

The arrival line carries only method, path, query and request headers. A wrongly typed value drops that
field with a warning on `eu.inqudium.limesium.reactive.logging.EndpointLogField`, never the event. The
throwable of a failed chain is attached to the event as its cause (`setCause`).

### 5.2 MDC keys

Set by `MdcScope` around each emission, and — depending on the variant and propagation setup — visible
inside handlers ([§2.6](#26-mdc-and-the-reactor-context)):

| Key | Scope on this stack |
|---|---|
| `endpoint_request_id`, `endpoint_method`, `endpoint_route` | the emission; the Reactor context; the handler MDC when enabled (Reactor variant with context propagation) or natively (coroutine variant) |
| `traceId`, `parentSpanId` | emission only |

Event-loop threads are pooled, and an outer filter may own the same keys — hence the restore-on-close
of `MdcScope`.

### 5.3 Levels and outcomes

The resolution order of the [common guide's §5.3](../../docs/GUIDE.md#53-levels-and-outcomes) — a
signalled error first, a 5xx the application handled after the stack's own disposition, `success`
otherwise, slowness raising severity — has one reactive-specific row, resolved between the error signal
and the 5xx:

| Condition | Level | `endpoint_outcome` |
|---|---|---|
| the subscription was cancelled | `WARN` | `cancelled` |

"The chain signalled an error" means `failure != null` — the `doOnError` signal in the Reactor variant,
the caught exception in the coroutine variant.

### 5.4 Meters

| Meter | On this stack |
|---|---|
| `endpoint.logging.events` | the `outcome` tag carries `cancelled` as the third value |
| `endpoint.logging.exchanges.open` | counts exchanges between filter entry (wiring) and the exactly-once completion — the terminal signal, or the commit for a deferred error |
| `endpoint.logging.failopen{stage=wiring}` | includes an unarmed deferral — a failed commit-callback registration, after which the event completes at the terminal signal instead ([§2.7](#27-fail-open-stages)) |
| `endpoint.request.body.read{state=partial}` | a subscription exists but no completion signal was observed — a cancelled subscription such as `take`, a client disconnect, an error mid-stream |

Registration conflicts are warned once per meter name on
`eu.inqudium.limesium.reactive.logging.EndpointLoggingMetrics`.

### 5.5 Reading the meters together

In the [common guide's table](../../docs/GUIDE.md#55-reading-the-meters-together), "the emission point
never fired" means on this stack: the terminal signal never arrived, or a deferred error's commit never
happened — nothing throws, so no fail-open count, and the `exchanges.open` baseline grows monotonically.
`events{outcome="cancelled"}` answers whether clients are disconnecting.

Note on the gauge: an exchange deferred to a commit that never happens is **intended** to stay open —
that is the liveness signal, not a leak to suppress
([§2.4](#24-emission-point-terminal-signal-commit-deferred-on-error)).

### 5.6 Trace correlation

The `traceparent` parse, the keys and the strict conformance are the
[common guide's §5.6](../../docs/GUIDE.md#56-trace-correlation). On this stack the event-loop thread
that runs the filter carries no tracing-bridge MDC at filter time — which is why the module reads the
incoming header in the first place. What the **emitting** thread carries depends on the propagation
mode: under Boot's default `limited` it carries no per-request state; under
`spring.reactor.context-propagation=auto` ([§3.5](#35-enabling-handler-side-mdc)) Micrometer's
`ObservationThreadLocalAccessor` restores the server span's `traceId`/`spanId` around every operator,
the terminal and commit callbacks included. The emission scope therefore **owns** the trace keys, exactly
like the servlet twin's: it installs the parsed pair, removes an unparsed one and the bridge's local
`spanId` for the duration of the event, and restores whatever was there afterwards — so a traced
exchange never publishes the bridge's span under `spanId`, and a traceless exchange carries no trace
context although the bridge traces it (ADR-0002: the trace id is the request id, or there is none).
Inside handlers the local `spanId` is the bridge's — the module never touches that key outside its
emission scope. `RequestLoggingWebFilterTracingIntegrationTest` pins the header-parse join, the identity
decision, the documented no-`traceparent` boundary and the commit-deferred error path against a real
Brave bridge on Netty under `limited`; `RequestLoggingWebFilterTracingAutoPropagationIntegrationTest`
pins the ownership against the same bridge under `auto`, where its MDC is live around the emission.

---

## 6. Special characteristics

The characteristics shared by both stacks — the one-instance-per-registry limitation of the gauge, the
masking fingerprint, the shared code inlined from `limesium-common` — and the complete list of
deliberate differences to the servlet twin are the
[common guide's §6](../../docs/GUIDE.md#6-shared-characteristics). What follows is reactive-only.

### 6.1 Cancellation and the missing status

A client disconnect cancels the subscription; `doFinally` fires with `CANCEL`, the exchange is emitted
immediately at WARN with `endpoint_outcome=cancelled`. If the response never committed, no status is
known: the message shows `-> -`, and `endpoint_response_status_code` is **omitted** rather than invented.
Dashboards must treat `endpoint_outcome` as the authoritative disposition and not assume the status
field is always present.

### 6.2 Error rendering bypasses the response tee

The response decorator sees only what is written through the **mutated** exchange the filter passes
down the chain. An **unhandled** error travels up to Spring's `WebExceptionHandler`s, and Boot's error
renderer writes the 500 body through the **original** response — those bytes bypass the tee. The event
still carries the rendered **status** (the commit callback observes the shared delegate), but
`endpoint_response_body` and the response-size sample stay absent for globally rendered error responses.
Responses rendered locally — a controller's `ResponseEntity`, a `@ControllerAdvice` — traverse the tee
normally. Pinned by the error-path integration test.

### 6.3 Zero-copy responses

`CapturingResponseDecorator` deliberately does **not** implement `ZeroCopyHttpOutputMessage`. Writers
check the response instance for that interface; wrapping makes file-serving handlers fall back to the
buffered path, so the bytes flow **through** the tee and are captured correctly — at the price of losing
the zero-copy optimisation while body capture or measuring is enabled. With both off, the exchange is not
decorated and zero-copy is untouched. Implementing the interface would silently re-open a capture bypass;
the mechanism is pinned by test.

### 6.4 Late body chunks after cancellation

Reactive Streams permits an already-requested `onNext` to arrive **after** a cancellation — on another
thread, after `doFinally` ran. The capture therefore does not rely on a single-writer assumption: every
mutation and read is under one lock, and the emitter's first step is `freeze()`. From then on a late tee
call is a no-op, so the logged body text and the size sample are one consistent snapshot instead of a
moving target.

### 6.5 Coroutine boundary and exception copies

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

---

### 6.6 Framework-parsed bodies bypass the tee

`CapturingRequestDecorator` tees `getBody()`. Form and multipart data are read through
`ServerWebExchange.getFormData()` / `getMultipartData()` — and the mutated exchange the filter passes
down the chain is a `ServerWebExchangeDecorator` that delegates both to the **original** exchange, whose
form-data and multipart publishers were built from the undecorated request at construction. A
`@ModelAttribute` bound from a form POST, a `FilePart`, an explicit `getFormData()` therefore never
subscribe to the decorated body (`@RequestParam` binds query parameters only on this stack): `endpoint_request_body` stays absent although `log-request-body` is on, no size
sample is recorded, and `endpoint.request.body.read` counts the exchange as `unread`. The response side
is unaffected. A documented boundary — the tee mirrors what the application subscribed to, and the
framework's form reader subscribes elsewhere — pinned by the form-POST case of the `ServerContract`
on every reactive server. Read the `unread` share of the read counter per `uri` with this in mind: a
form endpoint sits at 100 % `unread` by construction, not because it drops its payload.

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
    │   ├── RequestLoggingProperties.kt            endpoint-logging.* binding, Variant (HeaderLogProperties: common guide §6.4)
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
    │   (Traceparent, Mdc, NanoTimeSource, CorrelationIdGenerator, HeaderValueMasker and
    │    reportQuietly live in ../limesium-common - inlined into this jar, common guide §6.4)
    ├── main/resources/META-INF/spring/…AutoConfiguration.imports
    └── test/kotlin/eu/inqudium/limesium/reactive/logging/  see the suite overview below
```

Test-suite overview (the generated [test-evidence page](https://inqudium.github.io/limesium/tests/test-evidence/)
lists every test with its rationale):

| Suite | Scope |
|---|---|
| Unit suites (`RequestLoggingWebFilterTest`, `CoRequestLoggingWebFilterTest`, `…BodyAndHeaderTest`, `…MetricsTest`, `BoundedBodyCaptureTest`, `MdcContextPropagationTest`, `RequestLoggingAutoConfigurationTest`, …) | mock-exchange driven, deterministic; both filter variants against the shared lifecycle |
| `RequestLoggingWebFilterIntegrationTest` | end-to-end on real embedded **Netty** with the auto-selected (coroutine) variant: DataBuffer tee on pooled buffers, real WebFlux dispatch, commit-deferred error emission |
| Server suites (`ServerContract` run as `ReactorNettyServerIntegrationTest`, `TomcatServerIntegrationTest`, `JettyServerIntegrationTest`) | the **Reactor variant** (coroutine auto-configuration excluded — the majority consumer configuration) on every reactive server Boot 4 ships: the single active filter, a real round trip with both bodies teed on the server's own buffers, the commit-deferred emission behind the server's error rendering, a later commit action's status and header as the server orders the actions, the handler pattern of a real dispatch |
| `CoRequestLoggingWebFilterCoroutineIntegrationTest` | the **coroutine variant**'s `MDCContext` handler-MDC parity across real dispatcher hops |
| `RequestLoggingWebFilterTracingIntegrationTest` | ADR-0002 trace contract beside a real Brave bridge on Netty under Boot's default `limited` propagation: header-parse join, identity decision, the documented no-`traceparent` boundary, the commit-deferred error path |
| `RequestLoggingWebFilterTracingAutoPropagationIntegrationTest` | the emission scope's ownership of the trace keys beside the same bridge under `spring.reactor.context-propagation=auto`, where the bridge's `traceId`/`spanId` are live around the terminal and commit callbacks: parsed pair wins, no `spanId`, no trace context on a traceless exchange |
| Lockstep/contract tests (`TwinContractTest`, `EndpointLogFieldTest`, `EndpointLoggingReferenceConfigTest`, `HandlerMappingAttributeTest`) | pin the twin/wire/config contracts against the servlet twin and the shared reference YAML |

Fuzzing of the shared `Traceparent` parser and header masking lives in limesium-common. This module's
engine matrix is the three reactive servers Boot 4 ships - Reactor Netty natively, Tomcat and Jetty
through Spring's `HttpHandler` adapters over a servlet async cycle; Undertow left Boot with 4.0 and has
no reactive factory to run against. WebFlux has no per-container WAR story, so the matrix is one of
embedded servers, not of deployment targets like the servlet twin's.

### 7.2 Related documents

- [Common guide](../../docs/GUIDE.md) — everything shared by both modules: the exchange line, the shared
  architecture, dependency and encoder setup, the configuration namespace, the field family, the
  meters, the trace contract, and the [table of stack differences](../../docs/GUIDE.md#61-differences-between-the-stacks).
- [`README.md`](../README.md) — module summary, the twin-difference table, the duplication decision.
- [`limesium-servlet-logging/docs/GUIDE.md`](../../limesium-servlet-logging/docs/GUIDE.md) — the
  reference implementation's guide: what the servlet stack decides.
- [`docs/endpoint-logging-reference.yml`](endpoint-logging-reference.yml) — this module's complete
  commented configuration reference (the shared namespace plus `variant`), bound together with the
  servlet twin's file by `EndpointLoggingReferenceConfigTest`.
- [`/docs/elk/README.md`](../../docs/elk/README.md) —
  the Elasticsearch component template for the `endpoint_*` fields.
