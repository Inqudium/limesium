# Limesium — Common guide

One structured `endpoint_*` log line per HTTP exchange, at the service's own boundary. Limesium ships
two modules that do this —
[`limesium-servlet-logging`](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/README.md)
for Spring MVC / servlet applications and
[`limesium-reactive-logging`](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/README.md)
for Spring WebFlux — with the **same message format, the same field family, the same
`endpoint-logging.*` configuration and the same meters**. A dashboard, alert or index mapping must not
care which stack produced an event.

This guide is the part of the documentation that is **the same on both stacks**: what an exchange line
is, the shared architecture, the dependency and encoder setup, the whole configuration namespace, the
field family, the meters and the trace contract. Each module has its own guide for everything the
**stack decides** — how the filter is wired and ordered, where the event is emitted, how bodies are
teed, what the MDC covers, and the stack's own dispositions and edge cases:

- the [servlet guide](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md)
  of the reference implementation, with the per-engine
  [container guide](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/CONTAINERS.md)
  (Tomcat, Jetty, Undertow);
- the [reactive guide](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md)
  of the WebFlux twin, including the coroutine variant.

Everything here is derived from the code under `limesium-common` and the two modules; when the two
disagree, the code wins. Where a section notes a stack difference, [§6.1](#61-differences-between-the-stacks)
collects them all.

## Table of contents

1. [Introduction](#1-introduction)
   1. [What the modules do](#11-what-the-modules-do)
   2. [What the modules deliberately do not do](#12-what-the-modules-deliberately-do-not-do)
   3. [The exchange line](#13-the-exchange-line)
   4. [The two modules and the cross-stack contract](#14-the-two-modules-and-the-cross-stack-contract)
2. [Shared architecture](#2-shared-architecture)
   1. [Shared components](#21-shared-components)
   2. [Exchange identity](#22-exchange-identity)
   3. [The body tee: capture mirrors consumption](#23-the-body-tee-capture-mirrors-consumption)
   4. [Fail-open contract](#24-fail-open-contract)
   5. [Injectable collaborators](#25-injectable-collaborators)
3. [Using it in a foreign project](#3-using-it-in-a-foreign-project)
   1. [Prerequisites](#31-prerequisites)
   2. [Adding the dependency](#32-adding-the-dependency)
   3. [Wiring](#33-wiring)
   4. [Overriding the collaborator beans](#34-overriding-the-collaborator-beans)
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
6. [Shared characteristics](#6-shared-characteristics)
   1. [Differences between the stacks](#61-differences-between-the-stacks)
   2. [One metrics instance per registry](#62-one-metrics-instance-per-registry)
   3. [Masking is a fingerprint, not a secret](#63-masking-is-a-fingerprint-not-a-secret)
   4. [Shared code: limesium-common, inlined by Shade](#64-shared-code-limesium-common-inlined-by-shade)
7. [Appendix](#7-appendix)
   1. [Related documents](#71-related-documents)

---

## 1. Introduction

### 1.1 What the modules do

Both modules are Spring Boot auto-configured filters — a `OncePerRequestFilter` plus a
`ServletRequestListener` on the servlet stack, a `WebFilter` on the reactive stack. For every inbound
HTTP exchange each of them:

- resolves the exchange identity per [ADR-0002](adr/ADR-0002-trace-id-is-the-request-id.md): a
  conformant `traceparent`'s trace id **is** the request id; only a traceless exchange adopts a
  correlation id from the configured request header (or generates one) and echoes it back on the
  response — a traced exchange passes through observationally untouched ([§2.2](#22-exchange-identity));
- optionally logs an **arrival line** the moment the request comes in;
- measures the exchange duration with an injectable monotonic time source;
- optionally tees the request and response bodies as they flow (bounded, never buffered or replayed);
- optionally records the selected request/response headers, with stable masking of sensitive values;
- parses the W3C `traceparent` header at filter entry (`traceId`/`parentSpanId`) so the event stays
  joinable with its trace;
- emits **exactly one** structured completion event on a dedicated logger — with the outcome, status,
  duration, path, handler template and the optional headers/bodies as SLF4J key-values — at the point
  where the status is **final**: request destruction on the servlet stack, the terminal signal
  (commit-deferred on error) on the reactive stack;
- feeds six Micrometer meters that observe the logging itself (fail-open counts, emitted events, open
  exchanges, body sizes, request-id origin).

All of this is **fail-open**: no failure inside the logging — wiring, body tee, MDC adapter, emission,
metrics — can ever fail, delay or alter the request it describes ([§2.4](#24-fail-open-contract)).

Where the identity is available **during** the exchange is the one thing the stacks answer
differently: the servlet module puts `endpoint_request_id`, `endpoint_method` and `endpoint_route` into
a thread-local MDC for the whole filter chain (and onto the Spring MVC async worker thread); the
reactive module writes the same keys into the Reactor context and restores them into the handler's MDC
through opt-in context propagation, or natively in its coroutine variant. Each module guide's §1.1
lists what its stack adds.

### 1.2 What the modules deliberately do not do

- **No request rates, latencies or status distributions as metrics.** Boot's `http.server.requests` and
  the structured log fields cover those; the modules' meters observe only what those cannot show
  ([§5.4](#54-meters)).
- **No body masking or transformation, and no per-key response sampling.** Both were considered and
  dropped on purpose; bodies are logged verbatim up to the capture limit. If a body may carry personal
  data, leave `log-*-body` at `never`.
- **No sampling.** Every matching exchange emits one event; the logger level is the only volume control
  ([§4.5](#45-logger-levels)), and `on-failure` body logging the only body-volume control
  ([§4.3](#43-body-logging-and-body-measuring)).
- **No replaying body cache.** The tee is passive; an unread request body is logged as absent.
- **No exporting of a `MeterRegistry`.** The host's registry is consumed if present; otherwise a private
  `SimpleMeterRegistry` absorbs the values.

Each stack has one further non-goal of its own — no MDC on application-owned threads (servlet), no
chain-wide thread-local MDC by itself (reactive); see the module guides' §1.2.

### 1.3 The exchange line

On the logger `endpoint-http-exchange` (configurable) a completed exchange looks like this in a plain-text
appender:

```
Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 parentSpanId=00f067aa0ba902b7]
```

The trace suffix appears only when the request carried a conformant W3C `traceparent` header — its
trace id then doubles as the request id (ADR-0002). Alongside the message, the event carries SLF4J
key-values that a structured encoder turns into fields:

```json
{
  "message": "Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 parentSpanId=00f067aa0ba902b7]",
  "level": "INFO",
  "logger": "endpoint-http-exchange",
  "endpoint_outcome": "success",
  "endpoint_duration_ms": 17,
  "endpoint_request_method": "GET",
  "endpoint_response_status_code": 200,
  "endpoint_url_path": "/api/things/42",
  "endpoint_url_template": "/api/things/{id}",
  "endpoint_request_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "endpoint_method": "GET",
  "endpoint_route": "/api/things/42",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "parentSpanId": "00f067aa0ba902b7"
}
```

The `endpoint_request_id` / `endpoint_method` / `endpoint_route` / `traceId` / `parentSpanId` entries come from
the MDC ([§5.2](#52-mdc-keys)); the `endpoint_*` key-values are the field family of [§5.1](#51-log-fields).
How MDC entries land in the document (flat, nested, renamed) is the encoder's decision. The servlet
module adds one field, `endpoint_async`; the reactive module never emits it ([§5.1](#51-log-fields)).

With the optional arrival line enabled, a second, earlier line precedes it:

```
Endpoint http exchange started GET /api/things/42 [endpoint_request_id=0f7c1a2e-...]
```

The arrival line carries no outcome, status or duration, so a dashboard keyed on `endpoint_outcome`
still sees exactly one event per exchange.

### 1.4 The two modules and the cross-stack contract

The servlet module is the **reference implementation** and owns the cross-stack contract files; the
reactive module's build binds them:

| Contract | Shipped in | Pinned by |
|---|---|---|
| Configuration keys and defaults | [`/docs/endpoint-logging-reference.yml`](endpoint-logging-reference.yml) — the reactive module carries a copy plus its one `variant` key as [`limesium-reactive-logging/docs/endpoint-logging-reference.yml`](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/endpoint-logging-reference.yml) | `EndpointLoggingReferenceConfigTest` in both modules: binds the YAML against the module's `RequestLoggingProperties` (the reactive one binds both files and pins the key parity) |
| Field family and index mapping | [`/docs/elk/…component-template.json`](elk/README.md) | `EndpointLogFieldTest` in both modules: locks the module's `EndpointLogField` enum against the template |
| Message text and meter names | the servlet module's emitter and metrics | `TwinContractTest` in both modules |

The reactive build pulls the two shared files from the sibling checkout as **test resources** (declared
in its `pom.xml`), so a missing sibling fails at resource processing with a clear message rather than as
a silent contract drift. The consequence for a consumer: a dashboard, alert or index mapping written for
one stack works unchanged for the other.

---

## 2. Shared architecture

### 2.1 Shared components

Both modules are built from the same five layers — auto-configuration, the stack's filter lifecycle,
state and emission, capture, cross-cutting — and from the same set of named components. Some of them are
one byte-identical class inlined into both jars from `limesium-common`
([§6.4](#64-shared-code-limesium-common-inlined-by-shade)); the others are per-stack twins with the same
name and contract whose code genuinely differs:

| Component | Responsibility | Shared how |
|---|---|---|
| `RequestLoggingProperties` | The `endpoint-logging.*` binding, validated in `init` | per-stack twin; the reactive one adds `variant` |
| `HeaderLogProperties` | One header section — `includes` / `excludes` / `masked` / `unmasked` — with the selection and the masking fingerprint ([§4.2](#42-header-sections)) | byte-identical (`limesium-common`) |
| `ExchangeLogEmitter` | Builds and emits the arrival line and the completion event; resolves level, outcome and cause; records body sizes; opens the emission `MdcScope` | per-stack twin (the outcome vocabulary differs) |
| `EndpointLogField` | The wire names and the exact JVM type of each structured field; a wrongly typed value drops the field with a warning, never the event | per-stack twin, each locked against the one template |
| `EndpointLoggingMetrics` | The six meters — the fixed-tag meters pre-registered, the body meters created lazily per tag — with per-meter fallback to a private registry on registration conflict | per-stack twin (meter descriptions carry the stack's outcome vocabulary) |
| `BoundedBodyCapture` | The bounded capture target; count-only mode with limit `0`; the request-side read state (`BodyReadState`) | per-stack twin (two concurrency designs, [§6.1](#61-differences-between-the-stacks)) |
| `Traceparent` | Strict W3C `traceparent` parsing to `(traceId, parentSpanId)` ([§5.6](#56-trace-correlation)) | byte-identical |
| `MdcKeys` / `TraceMdcKeys` / `MdcScope` | The MDC key names; the scope that puts identity (and, for the emission, the trace keys) into the MDC and restores the previous values on close | byte-identical |
| `NanoTimeSource` / `CorrelationIdGenerator` / `HeaderValueMasker` | Injectable time, id and header masking ([§2.5](#25-injectable-collaborators)) | byte-identical |
| `reportQuietly` | Guards the diagnostics channel (counter + internal log) of every catch block | byte-identical |

The per-stack component overviews — the filter classes, the async and variant machinery, the capture
wrappers and decorators — are §2.1 of the
[servlet guide](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#21-component-overview)
and of the
[reactive guide](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#21-component-overview).

### 2.2 Exchange identity

At filter entry each module resolves **one** request id for the exchange
([ADR-0002](adr/ADR-0002-trace-id-is-the-request-id.md)), in this order:

| The request carries | Request id | Response echo | `correlation.id{source=…}` |
|---|---|---|---|
| a conformant W3C `traceparent` header | its **trace id** | none — the wire stays untouched | `trace` |
| no conformant `traceparent`, but the configured correlation header (`X-Correlation-Id` by default) | the header value | echoed on the response | `header` |
| neither | a generated id (`CorrelationIdGenerator`, [ADR-0004](adr/ADR-0004-counting-correlation-id-default.md)) | echoed on the response | `generated` |

The echo is set **once at filter entry** — downstream code that sets the header itself decides what the
client finally sees; event and MDC keep the id resolved at entry. The id is the `endpoint_request_id`
MDC entry ([§5.2](#52-mdc-keys)) and the inline `[endpoint_request_id=…]` in the message; the generator
is never consulted for a traced exchange. Which `traceparent` counts as conformant is
[§5.6](#56-trace-correlation).

### 2.3 The body tee: capture mirrors consumption

Bodies are never pre-read, buffered or replayed. Each module installs a **passive tee** — servlet
stream/reader/writer wrappers on the one stack, `DataBuffer` decorators on the other — whose target is
`BoundedBodyCapture`: a `ByteArrayOutputStream` of at most `max-body-bytes` and a total byte counter.
With limit `0` it runs in **count-only** mode for the body-size meters: nothing is buffered, every byte
is counted. The tee exists only when a body is logged (in any mode — `on-failure` needs the bytes before
the outcome is known, [§4.3](#43-body-logging-and-body-measuring)) **or** measured; without either, the
chain receives the original request and response.

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

The mechanics — what is wrapped, what bypasses the tee, how the capture is made visible to the emitting
thread — are the
[servlet guide's §2.6](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#26-the-body-tee)
and the
[reactive guide's §2.5](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#25-the-body-tee).

### 2.4 Fail-open contract

A logging component must never fail the request it describes. Both modules enforce that at every
boundary where they call host-provided code (MDC adapter, appenders, `MeterRegistry`, the container's or
server's request/response facade). Every failure is swallowed, counted on `endpoint.logging.failopen`
by **stage**, and reported on the module's own logger:

| Stage | Meaning | What happens | Counted as |
|---|---|---|---|
| wiring | bookkeeping around one exchange failed: constructing the captures, resolving the identity, opening or closing an MDC scope, reading the handler template, recording a sample or a counter | the filter degrades to a plain pass-through for this request, or the one piece of bookkeeping is lost — the event usually still follows | `failopen{stage=wiring}` |
| arrival | `logRequestStart`, including its level gate | the arrival line is dropped | `failopen{stage=arrival}` |
| emission | `logExchange` — everything after the exactly-once gate | the exchange event is **lost** | `failopen{stage=emission}` |
| registration | `EndpointLoggingMetrics.registerOrFallback` | the conflicting meter lives in a private registry, warned once per name ([§5.4](#54-meters)) | — |

Every catch block reports through `reportQuietly`, which swallows a failure of the diagnostics channel
itself (a throwing `Counter`, a throwing appender that also covers the internal logger) — there is
nothing left to report to. `InterruptedException` is caught separately and the interrupt flag is
restored before the failure is recorded.

Failures of the logging are reported on the module's **own** loggers under
`eu.inqudium.limesium.servlet.logging` resp. `eu.inqudium.limesium.reactive.logging` (the filter, the
emitter, the metrics), never on the exchange logger, so the exchange stream stays parseable. The exact
boundaries per stack — which method, which stage — are the
[servlet guide's §2.8](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#28-fail-open-stages)
and the
[reactive guide's §2.7](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#27-fail-open-stages).

**Security note.** Fail-open is the inverse of what an audit log needs: a host-side fault silently
removes the exchange from the log instead of failing the request. The exchange log is therefore an
**observability** feature with no completeness guarantee; a regulatory audit trail must come from a
fail-closed component. The compensating controls are `endpoint.logging.failopen` and the
`exchanges.open` gauge ([§5.5](#55-reading-the-meters-together)) — alert on them.

### 2.5 Injectable collaborators

Time and randomness are injected, not ambient:

- `NanoTimeSource` — monotonic nanoseconds for `endpoint_duration_ms` and the slow threshold; the single
  production read of `System.nanoTime()` is `NanoTimeSource.SYSTEM`. Log timestamps come from the
  logging backend, keeping the two time domains separate.
- `CorrelationIdGenerator` — the id for traceless requests without a correlation header;
  `CorrelationIdGenerator.DEFAULT` (a counting generator: random per-instance base-36 prefix plus
  counter, 21 characters — [ADR-0004](adr/ADR-0004-counting-correlation-id-default.md)) by default.
  Never consulted for a traced exchange (ADR-0002: the `traceparent` trace id is the request id).
- `HeaderValueMasker` — how a header listed in a `masked` section renders on the line; `DEFAULT` is the
  stable `length:hash` fingerprint ([§6.3](#63-masking-is-a-fingerprint-not-a-secret)). The properties
  decide WHICH values are masked, the bean decides HOW — a keyed HMAC for a compliance regime, a fixed
  `***` for a host that wants no correlation at all.

All three are `fun interface`s, all three are `@ConditionalOnMissingBean` beans
([§3.4](#34-overriding-the-collaborator-beans)), and all are what the modules' tests drive from an
`AtomicLong` / a fixed string / a lambda without any mocking library.

---

## 3. Using it in a foreign project

### 3.1 Prerequisites

| Requirement | Notes |
|---|---|
| Spring Boot 4.x web application of the module's type | each module is conditional on its web application type (`@ConditionalOnWebApplication(type = SERVLET)` resp. `REACTIVE`) and inert in the other. An application may carry both jars: the matching one activates, the other stays inert — keep them at the same version, both inline the same shared classes |
| Java 21, Kotlin stdlib on the runtime classpath | the modules are written in Kotlin; a Java host only needs `kotlin-stdlib`, which the jar pulls transitively |
| SLF4J 2.x binding (Logback by default in Boot) | the modules use the fluent `LoggingEventBuilder` API (`addKeyValue`) |
| Micrometer core | present via the web starter; an actuator `MeterRegistry` is optional |

Both modules are **libraries**, not starters: they declare `spring-boot-autoconfigure`, `slf4j-api`,
`spring-web`, `micrometer-core` and `kotlin-stdlib` — plus the `provided` Jakarta Servlet API on the
servlet stack and `reactor-core` on the reactive stack — and nothing else: no logging backend, no YAML,
no container or server are forced onto the host.

What the stack adds — supported containers and the Servlet API on the one side, the optional coroutine
and context-propagation libraries on the other — is §3.1 of the
[servlet guide](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#31-prerequisites)
and of the
[reactive guide](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#31-prerequisites).

### 3.2 Adding the dependency

Pick the artifact for the host's stack:

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>limesium-servlet-logging</artifactId>   <!-- or limesium-reactive-logging -->
    <version><!-- current release: see the badges below --></version>
</dependency>
```

There is no BOM; the version is declared on the dependency itself. The current release is shown live by
the Maven Central badges:
[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/limesium-servlet-logging.svg?label=limesium-servlet-logging)](https://central.sonatype.com/artifact/eu.inqudium/limesium-servlet-logging)
[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/limesium-reactive-logging.svg?label=limesium-reactive-logging)](https://central.sonatype.com/artifact/eu.inqudium/limesium-reactive-logging)

That is all: the auto-configuration wires the filter ([§3.3](#33-wiring)), every exchange is logged on
the `endpoint-http-exchange` logger at INFO, the request id comes from the `traceparent` trace id
(traceless exchanges read/echo `X-Correlation-Id` instead — ADR-0002), and the six meters are registered
in the host's `MeterRegistry` if one exists.

To remove the module again without touching the classpath:

```yaml
endpoint-logging:
  enabled: false
```

### 3.3 Wiring

**Automatic wiring** is the module's auto-configuration, listed in the auto-configuration imports
resource and conditional on two things: the web application type of its stack, and
`endpoint-logging.enabled` (default `true`; `false` removes the filter and every default bean together).
There is nothing for the host to inject and nothing to build: every exchange the container or server
hands to the application passes the filter, and **path activation** (`include-path-patterns`,
`exclude-path-prefixes`) is evaluated inside the filter — never through a registration's URL mapping —
so its semantics are byte-identical on both stacks ([§4.4](#44-path-activation)). Both filters order
themselves at `Ordered.HIGHEST_PRECEDENCE + 10`: early enough that the traceless correlation echo is set
before anything else runs and everything after it sees the exchange identity.

What the wiring physically consists of is the stack's business — a filter registration plus the
completion listener that is the emission point on the servlet stack, one `WebFilter` bean that WebFlux
collects on the reactive stack — and so is **manual wiring**, needed when the container or handler is
assembled without Boot's web auto-configuration or outside a Spring context:

| | Automatic wiring | Manual wiring |
|---|---|---|
| servlet | [§3.2](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#32-automatic-wiring) | [§3.3](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#33-manual-wiring) |
| reactive | [§3.2](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#32-automatic-wiring) | [§3.3](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#33-manual-wiring) |

Rules that hold for a hand-wired filter on either stack:

- **One filter per `MeterRegistry`.** The meters are identified by name, so all filters on one registry
  share one metrics owner and the `endpoint.logging.exchanges.open` gauge reports the total across them
  ([§6.2](#62-one-metrics-instance-per-registry)). A second instance buys nothing.
- **Activation is not the host's business.** Path activation is evaluated inside the filter, so a
  manually wired filter applies the same rules as an automatically wired one; there is no need to map
  or add it selectively.
- **Ordering is the host's business.** The automatic wiring guarantees the early position; a manual
  registration lands where the host puts it. Keep it early — before everything that logs.
- **The overridable beans stay overridable.** Every constructor takes
  `(RequestLoggingProperties, NanoTimeSource, CorrelationIdGenerator, MeterRegistry)` plus an optional
  trailing `HeaderValueMasker` (the built-in fingerprint when omitted), and every default is public:
  `RequestLoggingProperties()` (or a `copy(...)` with the fields to change), `NanoTimeSource.SYSTEM`,
  `CorrelationIdGenerator.DEFAULT`, a `SimpleMeterRegistry()` or the registry the surrounding code owns.
- **Inside a Boot context with the auto-configuration switched off**, the host binds the properties
  class itself (`@EnableConfigurationProperties(RequestLoggingProperties::class)`), because that
  annotation lives on the auto-configuration that is now gone.

Everything else is unchanged by the way the filter was wired: emission point, outcomes, meters, header
sections, body capture and the fail-open contract behave exactly as under the automatic wiring — the
filter does not know how it got into the chain. The wiring itself is fail-open like everything else: a
failure while wiring one exchange degrades that request to a pass-through with a `stage=wiring` count
([§2.4](#24-fail-open-contract)).

### 3.4 Overriding the collaborator beans

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

Replacing the **filter bean** itself is stack-specific — on the servlet stack the auto-configured
registration and completion listener still wrap the host's filter
([servlet guide §3.5](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#35-replacing-the-filter-bean)),
on the reactive stack a host bean of either variant makes both auto-configurations back off
([reactive guide §3.6](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#36-replacing-the-filter-bean)).
Keep in mind the one-instance-per-registry limitation of the gauge
([§6.2](#62-one-metrics-instance-per-registry)).

### 3.5 Logging backend and structured output

The modules emit through SLF4J's fluent API. Every exchange event carries its data in **two places**, and
an encoder treats them differently:

| Data | Carried as | Examples |
|---|---|---|
| The field family | SLF4J **key-value pairs** (`addKeyValue`) | `endpoint_outcome`, `endpoint_duration_ms`, `endpoint_url_path`, `endpoint_request_body` |
| The identity and trace context | **MDC** entries, set by the emission scope (and, during the exchange, as the stack provides them) | `endpoint_request_id`, `endpoint_method`, `endpoint_route`, `traceId`, `parentSpanId` (from the `traceparent` header) |

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
13:54:58.534 INFO  [http-nio-8080-exec-3] endpoint-http-exchange - Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=4bf9… traceId=4bf9… parentSpanId=00f0…] endpoint_outcome=success endpoint_duration_ms=17 endpoint_request_method=GET endpoint_url_path=/api/things/42 endpoint_url_template=/api/things/{id} endpoint_response_status_code=200 [endpoint_method=GET, endpoint_request_id=4bf9…, endpoint_route=/api/things/42, traceId=4bf9…, parentSpanId=00f0…]
```

- `%kvp` quotes values with double quotes by default; `%kvp{NONE}` leaves them bare, `%kvp{SINGLE}` uses
  single quotes.
- `%X{endpoint_request_id:-}` prints one key and nothing when it is absent; `%mdc` prints every entry
  that is present as `key=value`, so the trace keys appear only on traced exchanges. A conditional prefix
  ("`traceId=` only when present") is not expressible in a pattern — use `%mdc` or a structured encoder.
- In Spring Boot the same pattern goes into `logging.pattern.console` without any XML.
- This is both modules' own test configuration (`src/test/resources/logback-test.xml`), so a test run
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
    endpoint-http-exchange: INFO
    eu.inqudium.limesium.servlet.logging: WARN    # or eu.inqudium.limesium.reactive.logging
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

Whatever the option, keep the module's own logger — `eu.inqudium.limesium.servlet.logging` resp.
`eu.inqudium.limesium.reactive.logging` — at WARN or lower: it carries the WARN breadcrumb on a thrown
chain and the module's own failure reports.

### 3.6 Index mapping (ELK)

The thirteen `endpoint_*` fields have a ready-made Elasticsearch component template in
[`/docs/elk/`](elk/README.md):

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

   Expect `X-Correlation-Id: demo-1` on the response and one `endpoint-http-exchange` line with
   `endpoint_request_id=demo-1`. With a `traceparent` header instead
   (`curl -i -H 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01' …`), expect
   **no** `X-Correlation-Id` response header and `endpoint_request_id=4bf92f…` plus
   `traceId=… parentSpanId=…` on the line (ADR-0002).

2. Log something inside the handler and confirm `endpoint_request_id` is on that line too — on the
   servlet stack always; on the reactive stack in the coroutine variant, or in the Reactor variant once
   handler-side MDC is enabled
   ([reactive guide §3.5](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#35-enabling-handler-side-mdc)).

3. Throw from a handler and confirm: an immediate WARN breadcrumb on the filter's own logger
   (`eu.inqudium.limesium.servlet.logging.RequestLoggingFilter` resp.
   `eu.inqudium.limesium.reactive.logging.RequestLoggingWebFilter`), then the exchange line with the
   rendered `500`, `endpoint_outcome=failure` at ERROR with the cause attached.

4. Check the meters (with actuator):

   ```bash
   curl -s localhost:8080/actuator/metrics/endpoint.logging.events
   curl -s localhost:8080/actuator/metrics/endpoint.logging.exchanges.open
   ```

   `events` should equal the number of logged lines; `exchanges.open` should be `0` when idle.

---

## 4. Configuration

All properties live under `endpoint-logging.*`, and the namespace is **identical** on both stacks — key
for key and default for default; the only reactive-only addition is `variant`. The complete, commented
reference with every default is [`/docs/endpoint-logging-reference.yml`](endpoint-logging-reference.yml)
(the reactive module ships a copy that adds `variant`); `EndpointLoggingReferenceConfigTest` binds it
against `RequestLoggingProperties` in both modules and fails the build on any drift — every key must
exist, every value must be the built-in default.

What a property means is the same on both stacks. Where the stack adds a nuance — what exactly an
excluded request skips, against which path the patterns match, what the slow threshold measures — §4 of
the [servlet guide](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#4-configuration-on-the-servlet-stack)
and of the [reactive guide](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#4-configuration-on-the-reactive-stack)
has it.

### 4.1 Property reference

| Property | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch. `false` makes the auto-configuration back off — no filter, no registrations, no default beans. A context-start decision, not a runtime toggle. |
| `variant` | `auto` \| `reactor` \| `coroutine` | `auto` | **Reactive-only** — selects the WebFlux filter variant; the servlet module has no such key. `auto` = coroutine variant when `kotlinx-coroutines-reactor` + `kotlinx-coroutines-slf4j` are present, Reactor otherwise; `reactor` forces the Reactor variant; `coroutine` requires the libraries and fails startup without them ([reactive guide §3.4](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#34-choosing-the-filter-variant)). |
| `logger-name` | string | `endpoint-http-exchange` | Logger of the arrival line and the exchange event. Its level is the runtime volume control ([§4.5](#45-logger-levels)). |
| `correlation-id-header` | string (RFC 9110 token) | `X-Correlation-Id` | Header the correlation id is read from on **traceless** exchanges (no conformant `traceparent` — ADR-0002); blank/absent means generated. Only such an exchange gets the echo, set once at filter entry — downstream code that sets the header itself decides what the client finally sees; event and MDC keep the id resolved at entry. A traced exchange takes its request id from the `traceparent` trace id, ignores this header and echoes nothing. |
| `include-query-string` | boolean | `true` | Log the query string as its own field `endpoint_url_query` (never part of the path). Disable when query parameters may carry personal data. |
| `log-request-start` | boolean | `false` | Additionally log an arrival line before the chain runs, at INFO, with the identity in the MDC. Carries no outcome/status/duration. |
| `include-path-patterns` | list of `PathPattern` | `[]` | Endpoints the filter is active for at all; empty = every endpoint. Parsed once at startup; an invalid pattern fails the context. |
| `exclude-path-prefixes` | list of strings | `[]` | Request-path prefixes the filter skips entirely — no event, no MDC, no correlation echo, no gauge movement. Prefix match against the decoded request path. An exclude always wins over an include. |
| `slow-request-threshold` | duration | `5s` | At/above this duration an INFO exchange escalates to WARN and is flagged `endpoint_slow: true`; the outcome stays `success`. Measured from filter entry to the emission point. Must be ≥ 1 ms. |
| `request-headers.includes` / `.excludes` / `.masked` / `.unmasked` | lists of header names | see [§4.2](#42-header-sections) | The request-header section. |
| `response-headers.includes` / `.excludes` / `.masked` / `.unmasked` | lists of header names | see [§4.2](#42-header-sections) | The response-header section. |
| `log-request-body` | `never` \| `on-failure` \| `always` | `never` | Tee the request body into `endpoint_request_body`, up to `max-body-bytes` — on every line (`always`) or only when the outcome is not `success` or the status is a 4xx (`on-failure`, [§4.3](#43-body-logging-and-body-measuring)). |
| `log-response-body` | `never` \| `on-failure` \| `always` | `never` | Tee the response body into `endpoint_response_body`, up to `max-body-bytes` — on every line or only when the outcome is not `success` or the status is a 4xx. |
| `measure-request-body-size` | boolean | `false` | Record `endpoint.request.body.size`; independent of `log-request-body`. |
| `measure-response-body-size` | boolean | `false` | Record `endpoint.response.body.size`; independent of `log-response-body`. |
| `max-body-bytes` | int > 0 | `16384` | Capture limit per body. Bounds **memory**, not the exchange: bytes beyond it still flow; the logged value is truncated with a note of the total size. |
| `masking-key` | string | *(empty)* | Keys the masking fingerprint: empty keeps the unkeyed `length:hash`, any other value turns it into an HMAC-SHA256 under the key — same shape, same stability under the same key, guess-proof without it. A **secret**: supply it like one; the properties' `toString` redacts it. Ignored when a host pins its own `HeaderValueMasker` bean. |

### 4.2 Header sections

Each direction has one section with four lists; matching is case-insensitive throughout. The section
is **masked by default** ([ADR-0005](adr/ADR-0005-headers-masked-by-default.md)): whatever it logs is
rendered as a fingerprint unless the name is explicitly allowed in plaintext, so the debugging move
`includes: ["*"]` costs readability, never confidentiality.

| List | Default | Semantics |
|---|---|---|
| `includes` | `[]` | Names to log. **Empty logs nothing** (the safe default). The entry `*` logs every header the message carries, deduplicated case-insensitively. |
| `excludes` | `[]` | Names removed from the included set — meaningful mainly with `*`. An exclude always wins. `*` is rejected here at binding time (an empty `includes` already logs nothing). |
| `masked` | `["*"]` | Names whose **value** is replaced by what the `HeaderValueMasker` bean renders — by default a fingerprint `length:hex`, the character length plus the first 64 bits of the SHA-256 of the UTF-8 value, e.g. `18:930bbdc51b6aed5c` (a **pseudonym**, not anonymisation: equal values stay recognisable as equal; key it with `masking-key` to stop guess confirmation). **The default masks every logged header** (ADR-0005). Narrow it to names, or empty it to switch masking off — a visible decision. Masking affects only headers that are logged; listing a name here does not include it. |
| `unmasked` | `[]` | Names that appear in **plaintext** although `masked` covers them — the explicit allowlist of harmless names (`Content-Type`, `Accept`, a correlation id). An unmasked name always wins over a masked one. `*` is rejected here: the plaintext set is a list of names by design; to log everything in plaintext, empty `masked` instead. |

Multi-valued headers are joined with `, `. The selected pairs are rendered into one display-only field
per direction as `[Name:"value", Name2:"value2"]`; nothing is emitted when the selection is empty or no
selected header is present.

Request headers are selected at **filter entry**; response headers at **emission**, so they reflect
what the chain and the error rendering set.

### 4.3 Body logging and body measuring

Per direction, a **mode** decides whether a body is logged and a **flag** decides whether its size is
measured — independent of each other:

| `log-*-body` | `measure-*-body-size` | Capture installed | Buffered | Effect |
|---|---|---|---|---|
| `never` | off | no | — | chain gets the original request/response, zero overhead |
| `always` | off | yes, limit `max-body-bytes` | up to the limit | field logged on every line; no size sample |
| `on-failure` | off | yes, limit `max-body-bytes` | up to the limit | field logged only when `endpoint_outcome` is not `success` or the status is a 4xx; no size sample |
| `never` | on | yes, limit `0` (count-only) | nothing | size sample recorded; no field |
| `always` / `on-failure` | on | yes, limit `max-body-bytes` | up to the limit | both |

**`on-failure` is the volume switch** ([ADR-0006](adr/ADR-0006-bodies-logged-by-outcome.md)).
`always` means every body of every exchange; what is nearly always wanted is bodies for the exchanges that
went wrong — `failure`, and the stack's own disposition (`timeout` on the servlet stack, `cancelled` on
the reactive stack) — which cuts the volume by orders of magnitude and hits exactly the lines a body is
wanted for. The emitter decides when the outcome is final. The request body flows before the outcome is
known, so `on-failure` captures it exactly like `always` does (bounded by `max-body-bytes`) and discards
it for a success: the capture is paid, the output is saved — and the output is what burdens the log
pipeline. The gate is wider than the outcome vocabulary ([§5.3](#53-levels-and-outcomes)) by one status
class: a `4xx` response keeps its `success` outcome — the application answered — but its bodies are logged in
`on-failure`, because the client's error is exactly what the body explains; a `5xx` is `failure` and logs as
well. A slow but healthy exchange stays `success` and logs no bodies.

Rules that hold for every combination on both stacks:

- The tee is passive: bytes are counted and (up to the limit) copied as they flow; nothing is pre-read,
  replayed or withheld. Streaming behaviour is untouched ([§2.3](#23-the-body-tee-capture-mirrors-consumption)).
- An **unread request body** is logged as absent; no size sample is recorded.
- Zero-byte bodies produce no field and no sample — the distribution describes bodies that exist.
- Truncation is **byte-bounded**, and the decoder leaves an incomplete trailing multi-byte sequence
  undecoded rather than rendering a replacement character: `…<prefix>... [truncated, 12345 bytes total]`.
- The log charset is the declared request/response encoding, UTF-8 when absent or unparsable.
- `measure-*` records what actually flowed, **exact beyond** `max-body-bytes`.
- `measure-request-body-size` additionally records `endpoint.request.body.read` — whether the application
  consumed the body completely, partially, or not at all ([§5.4](#54-meters)).

What bypasses the tee on each stack (container error rendering, buffer resets, raw async cycles,
zero-copy responses) is §4.3 of the
[servlet guide](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#43-body-rules)
and of the
[reactive guide](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#43-body-rules).

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
stack's router does — the **path within the application** (a configured context path resp. base path is
stripped first, exactly as in the handler mapping), parsed into segments that **decode for matching**
and drop path parameters — so `/api/**` matches `/app/api/things` under context path `/app`,
`/%61pi/things` is included by `/api/**` and `/%61ctuator/health` is excluded by `/actuator/health`,
exactly as the router would serve them. The logged `endpoint_url_path` stays raw and keeps the context
path.

### 4.5 Logger levels

Severity and semantic are decoupled: the level only decides how loud — and whether — a line is emitted;
`endpoint_outcome` carries the disposition ([§5.3](#53-levels-and-outcomes)). The level of the
`logger-name` logger therefore acts as the runtime volume control:

| `endpoint-http-exchange` level | Emitted |
|---|---|
| `INFO` | every exchange |
| `WARN` | failures the application handled (5xx), the stack's own disposition (a container async timeout on the servlet stack, a cancelled subscription on the reactive stack), slow exchanges — and thrown chains |
| `ERROR` | only exchanges whose chain threw or signalled an error (on the servlet stack also an errored async cycle) |
| `OFF` | nothing — and no event is even assembled |

Level and outcome are resolved **before** the event is built, so a disabled level costs no assembly, no
header selection, no body decoding. Metrics are recorded **before** the level gate and are unaffected by
it — except `endpoint.logging.events`, which by definition counts emitted events only.

### 4.6 Validation at startup

`RequestLoggingProperties.init` and `HeaderLogProperties.init` reject, with a message naming the
property:

- blank `logger-name` or `correlation-id-header`;
- a `correlation-id-header` that is not an RFC 9110 token (it is written to every traceless response; a
  non-token would be rejected per request by a strict container or server adapter and silently turn the
  filter into an unlogged pass-through);
- `max-body-bytes` ≤ 0;
- a blank (whitespace-only) `masking-key` — empty means unkeyed, whitespace is a worthless secret;
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
    endpoint-http-exchange: INFO
    eu.inqudium.limesium.servlet.logging: WARN    # or eu.inqudium.limesium.reactive.logging
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
    includes: [Content-Type, Content-Length, Set-Cookie]
    unmasked: [Content-Type, Content-Length]              # Set-Cookie stays a fingerprint
```

**Production profile with bodies** — bodies only for the exchanges that went wrong; the request body is
captured up to `max-body-bytes` per exchange and dropped on success:

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
    endpoint-http-exchange: WARN
```

**API-only scope with a custom correlation header:**

```yaml
endpoint-logging:
  correlation-id-header: X-Request-Id
  include-path-patterns:
    - /api/**
  include-query-string: false
```

A reactive host that wants handler-side MDC in the Reactor variant adds the propagation setup of the
[reactive guide's §4.6](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#46-example-reactor-host-with-handler-mdc).

---

## 5. Metrics and observation

### 5.1 Log fields

The structured fields of the completion event (the arrival line carries only method, path, query and
request headers). The index types are those of the shipped component template; `EndpointLogFieldTest`
keeps each module's enum in lockstep with it.

| Field | Type | Index | doc_values | When present | Notes |
|---|---|---|---|---|---|
| `endpoint_outcome` | keyword | yes | on | always | `success` / `failure` plus the stack's own disposition — `timeout` (servlet) or `cancelled` (reactive); the field dashboards split by; decoupled from the level |
| `endpoint_duration_ms` | long | yes | on | always | from the injected monotonic source; measured from filter entry to the emission point |
| `endpoint_request_method` | keyword | yes | on | always | |
| `endpoint_response_status_code` | short | yes | on | always — except a reactive cancellation that never committed | the final status at emission — a numeric label, never summed |
| `endpoint_url_path` | keyword | yes | **off** | always | the **raw** request path as sent (percent-encoding intact), ids and all — filter exactly, never group |
| `endpoint_url_template` | keyword | yes | on | when the framework (Spring MVC / WebFlux) recorded a handler pattern | the aggregation half of the path pair, e.g. `/api/things/{id}` |
| `endpoint_url_query` | keyword | yes | **off** | when the request had one and `include-query-string` is on | raw, as sent |
| `endpoint_slow` | boolean | yes | on | only when the threshold was reached | absence means fast |
| `endpoint_async` | boolean | yes | on | **servlet only**, always there; never emitted by the reactive module | `true` when the chain returned with async processing started; the reactive enum keeps the constant so both map the same template |
| `endpoint_request_headers` | keyword | **no** | off | when selected headers are present | display only, rendered `[Name:"value", …]` |
| `endpoint_response_headers` | keyword | **no** | off | when selected headers are present | display only |
| `endpoint_request_body` | keyword | **no** | off | when `log-request-body` admits the outcome and bytes flowed | display only, bounded |
| `endpoint_response_body` | keyword | **no** | off | when `log-response-body` admits the outcome and bytes flowed | display only, bounded |

Each field asserts the exact JVM type of its value (`EndpointLogField.format`): a wrongly typed value
drops **that field** with a warning on the module's `EndpointLogField` logger, never the event.

The throwable of a failed chain is attached to the event as its cause (`setCause`), so a structured
encoder renders the stack trace alongside the fields. Which further throwables a stack attaches (the
servlet async callbacks' ones) is the module guide's §5.1.

### 5.2 MDC keys

| Key | Value | Scope |
|---|---|---|
| `endpoint_request_id` | the request id: the `traceparent` trace id, or the accepted/generated correlation id (ADR-0002) — always set | the emission, and during the exchange as the stack provides it |
| `endpoint_method` | the HTTP method | same |
| `endpoint_route` | the request **path** (the template is not known at filter entry) | same |
| `traceId` | the trace id parsed from the `traceparent` header | emission only |
| `parentSpanId` | the caller's span id parsed from the `traceparent` header — never published as `spanId` | emission only |

`MdcScope` restores the previous value of every key on close (worker threads are pooled; an outer filter
may own the same keys), rolls back a partial install if the adapter throws mid-put, and restores
best-effort on close with the first failure rethrown and later ones suppressed.

Where the three identity keys are visible **during** the exchange — the whole filter chain and the MVC
async worker on the servlet stack; the Reactor context, opt-in handler MDC or `MDCContext` on the
reactive stack — is §5.2 of the
[servlet guide](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#52-mdc-keys)
and of the
[reactive guide](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#52-mdc-keys).

### 5.3 Levels and outcomes

Resolved in this order in `ExchangeLogEmitter`:

| Condition | Level | `endpoint_outcome` | Cause attached |
|---|---|---|---|
| the chain threw or signalled an error | `ERROR` | `failure` | the exception |
| the stack's own disposition — servlet: async cycle timed out (`WARN`, `timeout`) or errored (`ERROR`, `failure`); reactive: subscription cancelled (`WARN`, `cancelled`) | see the module guide's §5.3 | | |
| status ≥ 500 without any of the above (the application handled it) | `WARN` | `failure` | — |
| otherwise | `INFO` | `success` | — |
| … and the duration reached `slow-request-threshold` | `INFO → WARN` | unchanged, plus `endpoint_slow: true` | — |

Slowness raises severity; it never turns a completed exchange into a failure.

### 5.4 Meters

Six meters, all **consumed** from the host's `MeterRegistry` (an `ObjectProvider`; without one a
private `SimpleMeterRegistry` absorbs the values). All fixed-tag meters are **pre-registered at
construction**, so a `rate()` alert sees the zero before the first occurrence. Rates, latencies and
status distributions are deliberately left to `http.server.requests` and the log fields. The names are
identical on both stacks and pinned by `TwinContractTest`.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `endpoint.logging.failopen` | counter | `stage` = `emission` \| `arrival` \| `wiring` | Logging failures the fail-open path swallowed ([§2.4](#24-fail-open-contract)). `emission`: an exchange event was **lost**. `arrival`: a start line was lost. `wiring`: bookkeeping failed (pass-through degradation, a lost MDC scope, a lost sample or counter) — the event usually still follows. A lost log line cannot report itself through the same pipeline; this counter is the independent channel. |
| `endpoint.logging.events` | counter | `outcome` = `success` \| `failure` \| the stack's own disposition (`timeout` / `cancelled`) | Exchange events actually **emitted** on the exchange logger — after the level gate, arrival lines excluded. The reconciliation ground truth against the log index. |
| `endpoint.logging.exchanges.open` | gauge | — | Exchanges between filter entry and the exactly-once completion (request destruction on the servlet stack, the terminal signal or commit on the reactive stack). Hovers near the active-request count in health. |
| `endpoint.logging.correlation.id` | counter | `source` = `trace` \| `header` \| `generated` | Origin of each exchange's request id ([§2.2](#22-exchange-identity)); the meter name predates ADR-0002 and stays stable. |
| `endpoint.request.body.read` | counter | `uri` = handler pattern, `UNKNOWN` without one; `state` = `unread` \| `partial` \| `complete` | How far the application **consumed** the request body, opt-in via `measure-request-body-size`. Recorded once per exchange whenever the measuring tee exists — including bodyless requests the application never touched, which is the `unread` share the counter exists to show. `partial` = consumption started but the end of the body was never observed (what that looks like per stack is the module guide's §5.4). Created lazily per `uri`/`state` on first use, like the size summaries. |
| `endpoint.request.body.size` / `endpoint.response.body.size` | distribution summary, base unit `bytes` | `uri` = handler pattern, `UNKNOWN` without one | Bytes that **actually flowed**, opt-in via `measure-*-body-size`, independent of body logging and level. Exact beyond `max-body-bytes`. Zero-byte bodies record no sample. Created lazily per `uri` on first use. |

**Registration conflicts.** Micrometer rejects a registration whose id already exists with a different
meter type. Rather than aborting the context (at construction) or suppressing an exchange event (at the
lazy body-size registration), the conflicting meter falls back to a private registry, warned once per
meter name on the module's `EndpointLoggingMetrics` logger: the module keeps working and that meter is
simply not exported.

### 5.5 Reading the meters together

The meters are designed to cover each other's blind spots:

| Question | Signal |
|---|---|
| Are exchange events being lost **loudly** (something threw)? | `failopen{stage=emission}` > 0 |
| Are exchange events being lost **silently** (the emission point never fired — nothing throws, so no fail-open count)? | `exchanges.open` baseline grows monotonically instead of returning towards 0 |
| Is the **log pipeline** (appender, broker, index) losing events? | `sum(endpoint.logging.events)` over a window ≠ count of indexed `endpoint-http-exchange` documents for the same window |
| Did the upstream stop propagating identity (`traceparent` or correlation ids)? | the `generated` share of `correlation.id` rises |
| Are async cycles timing out (servlet) / are clients disconnecting (reactive)? | `events{outcome="timeout"}` resp. `events{outcome="cancelled"}` |
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

What "the emission point never fired" means on each stack — `requestDestroyed` not firing, a commit
that never happens — is the module guide's §5.5.

### 5.6 Trace correlation

The trace context comes from the incoming W3C `traceparent` header, parsed by the module at filter entry
with the full W3C validation (ADR-0002; the parser is the shared `Traceparent` from `limesium-common`,
inlined into both jars — [ADR-0003](adr/ADR-0003-limesium-common-inlined-by-shade.md)):

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
  and the exchange counts as traceless for the identity decision. The conformance is pinned by
  `traceparent/conformance.txt` in `limesium-common`.
- Since ADR-0002 the trace id also **is** the exchange's `endpoint_request_id`, and a traced exchange
  gets no `X-Correlation-Id` echo — the identity decision and the trace fields share the one strict
  parse.
- The ids ride the MDC only, never the key-values; the emission `MdcScope` restores the parsed pair
  around the event — as MDC fields for structured encoders, and inline in the message
  (`… traceId=… parentSpanId=…`) for plain-text appenders.

The emission scope **owns** the trace keys, a bridge's `spanId` included: a parsed id is installed, an
unparsed one is removed for the scope's lifetime, so a stale id on a pooled emission thread can never
join the event to a foreign trace or span. Without a (valid) `traceparent`, nothing is decorated — a
trace the bridge mints locally is deliberately not joined; such an exchange carries a generated request
id instead. Both modules pin this against a real Brave bridge running beside them; what each stack's
threads carry at filter time and at emission is the module guide's §5.6.

---

## 6. Shared characteristics

### 6.1 Differences between the stacks

Everything not listed here behaves identically in both modules. Each row links the section of the
module guide that explains the stack's side.

| Concern | Servlet module | Reactive module |
|---|---|---|
| Disposition vocabulary | `success` / `failure` / **`timeout`** — the container's async timeout | `success` / `failure` / **`cancelled`** — a client disconnect, the reactive reality; there is no container async timeout in WebFlux ([§6.1](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#61-cancellation-and-the-missing-status)) |
| `endpoint_async` | emitted, always | never emitted — everything is asynchronous, the flag would carry no information |
| `endpoint_response_status_code` | always present | absent for a never-committed cancellation |
| Emission point | `requestDestroyed`, after the container's error dispatch and after async completion ([§2.4](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#24-emission-point-request-destruction)) | the terminal signal; on an error with an uncommitted response deferred to the `beforeCommit` callback ([§2.4](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#24-emission-point-terminal-signal-commit-deferred-on-error)) |
| Never-completing error rendering | n/a — destruction always fires | the exchange stays **open on the gauge** instead of logging a wrong status |
| `endpoint_duration_ms` | request occupancy including error rendering and async waiting ([§6.1](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#61-duration-is-request-occupancy)) | until the terminal signal or the commit |
| Chain-wide MDC | thread-local, for the whole chain, plus the Spring MVC async worker ([§2.7](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#27-mdc-coverage)) | Reactor context + opt-in accessors (Reactor variant), or `MDCContext` in the coroutine variant ([§2.6](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#26-mdc-and-the-reactor-context)) |
| Trace context | parsed at filter entry, restored by the emission scope around the destruction callback — a pooled thread without per-request state; the chain scope leaves the trace keys to the bridge | the same parsing and keys; restored by the emission scope at the terminal signal |
| Body tee | stream/reader and stream/writer wrappers; `reset()`, `resetBuffer()` and `sendError` clear the capture ([§6.3](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md#63-buffer-clearing-operations-discard-the-capture)) | `DataBuffer` map-tee; no reset analog — emitted buffers are on their way to the client |
| Body capture concurrency | single writer, late reader; a volatile total as the happens-before edge | lock-guarded, frozen at emission — late chunks after cancellation ([§6.4](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#64-late-body-chunks-after-cancellation)) |
| Variant selection | one filter | `endpoint-logging.variant` (`auto` / `reactor` / `coroutine`, [§3.4](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md#34-choosing-the-filter-variant)) |
| Handler template attribute | Spring MVC's `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` | WebFlux's `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` — each mirrored as a constant so the module does not depend on the MVC/WebFlux jar; pinned by `HandlerMappingAttributeTest` on both sides |
| Engine matrix | Tomcat, Jetty, and Undertow as unsupported territory — one integration suite each ([container guide](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/CONTAINERS.md)) | Netty only — WebFlux has no per-container WAR story |

### 6.2 One metrics instance per registry

Micrometer deduplicates meters by id. A second `EndpointLoggingMetrics` instance against the same
registry shares the **counters** (increments merge) but not the **gauge**: the second gauge registration
is silently ignored and that instance's open-exchange movements become invisible. The auto-configuration
creates exactly one filter and therefore one instance; a host wiring additional filter instances against
one registry inherits this limitation knowingly.

### 6.3 Masking is a fingerprint, not a secret

By default `masked` replaces a header value with `length:sha256-prefix64` — stable, so a masked token
can still be correlated across events and modules (both stacks use the same scheme, and so does the
outbound sibling [Legatium](https://github.com/Inqudium/legatium)), and a 64-bit cryptographic prefix
makes accidental collisions negligible. It is **unsalted and unkeyed**: it prevents plaintext exposure,
not offline guessing. A reader with a candidate list (usernames, tenant names, short API keys) can
confirm a candidate by hashing it. Do not treat the default as a security boundary for guessable values;
omit such headers from the selection instead — or **key** it: `endpoint-logging.masking-key` turns the
fingerprint into an HMAC-SHA256 under the key, same shape and stability, guess-proof without the key (a
secret — supply it as one). For any other shape the masker is the `HeaderValueMasker` bean
([§2.5](#25-injectable-collaborators)): a host pins its own (a fixed `***` for no correlation at all)
once, and both stacks mask with it. The contract a replacement must keep: never return the plaintext.

### 6.4 Shared code: limesium-common, inlined by Shade

The BYTE-identical part of the twins' shared layer lives in the `limesium-common` module
([ADR-0003](adr/ADR-0003-limesium-common-inlined-by-shade.md)): the `Traceparent` parser (with its
tests and fuzz target), `HeaderLogProperties` (selection and masking fingerprint, with its unit test and
fuzz target — ADR-0003 amendment 2026-08-31), `NanoTimeSource`, `CorrelationIdGenerator`,
`HeaderValueMasker`, `reportQuietly`, and the MDC keys and scope. The Maven Shade plugin inlines those
classes into each module's jar at package time, the dependency-reduced POM drops the dependency, and
`limesium-common` is never published — consumers keep adding exactly one artifact, and the shared
classes stay `internal` (`-Xfriend-paths`).

Everything whose twin copies genuinely differ stays deliberately duplicated, per the original
architecture-review decision: the field enum and metrics (per-stack outcome vocabulary and meter
descriptions), the emitters and exchanges, the properties (`variant` is reactive-only; the header
sections themselves are the shared `HeaderLogProperties`), and `BoundedBodyCapture` (two different
concurrency designs). For those the accepted cost is unchanged: a change is a conscious port in **both**
directions, and the lockstep tests catch *named* contract drift (keys, field names, meter names,
message text), not behavioural drift inside near-identical code.

---

## 7. Appendix

### 7.1 Related documents

- [Servlet guide](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md)
  — what the servlet stack decides: the filter and its two registrations, request destruction as the
  emission point, async exchanges, the chain-wide MDC, the wrappers, and the servlet-only edge cases.
- [Container guide](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/CONTAINERS.md)
  — Tomcat, Jetty and Undertow documented individually: destruction models, error paths, pinned
  deviations, suites.
- [Reactive guide](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md)
  — what the reactive stack decides: the two filter variants, the terminal signal with the
  commit-deferred error path, the Reactor context and handler-side MDC, the `DataBuffer` tee, and the
  reactive-only edge cases.
- The module READMEs —
  [servlet](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/README.md),
  [reactive](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/README.md) —
  each with its quick start and the twin-difference table.
- [`/docs/endpoint-logging-reference.yml`](endpoint-logging-reference.yml) — the complete commented
  configuration reference; every key and default, bound by `EndpointLoggingReferenceConfigTest` in
  both modules.
- [`/docs/elk/README.md`](elk/README.md) — the Elasticsearch component template for the `endpoint_*`
  fields and the access pattern behind each mapping decision.
- The decision records — [ADR-0002](adr/ADR-0002-trace-id-is-the-request-id.md) (the trace id is the
  request id), [ADR-0003](adr/ADR-0003-limesium-common-inlined-by-shade.md) (the shared code is
  inlined), [ADR-0004](adr/ADR-0004-counting-correlation-id-default.md) (the counting id default),
  [ADR-0005](adr/ADR-0005-headers-masked-by-default.md) (headers masked by default),
  [ADR-0006](adr/ADR-0006-bodies-logged-by-outcome.md) (bodies logged by outcome).
- The generated [test-evidence page](https://inqudium.github.io/limesium/tests/test-evidence/) — every
  test of both modules and of `limesium-common` with its rationale.
