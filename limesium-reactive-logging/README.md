# limesium-reactive-logging

The **WebFlux twin of [`limesium-servlet-logging`](../limesium-servlet-logging/README.md)**: an
auto-configured `WebFilter` that logs one structured `endpoint_*` line per HTTP exchange — with the
**identical message format, identical field family, identical `endpoint-logging.*` configuration and the
identical meters**. A dashboard, alert, or index mapping must not care which stack produced an event.

The long-form guide — introduction, architecture, integration into a foreign project, configuration,
metrics and the stack-specific behaviours — is [`docs/GUIDE.md`](docs/GUIDE.md); what the module does
and deliberately does not do is its [§1.1](docs/GUIDE.md#11-what-the-module-does) and
[§1.2](docs/GUIDE.md#12-what-the-module-deliberately-does-not-do).

The servlet module is the reference implementation; its documentation applies here too:

- **Configuration:** the complete commented reference for THIS module is
  [`docs/endpoint-logging-reference.yml`](docs/endpoint-logging-reference.yml) (the shared
  namespace plus the one reactive-only `variant` key) — this module's
  `EndpointLoggingReferenceConfigTest` **binds both files against this module's properties class** and
  pins the key parity, so neither reference can drift from the code or from its twin. The properties
  are explained in the guide's [§4](docs/GUIDE.md#4-configuration).
- **Index mapping:** the one component template for both stacks is the repository-shared
  [`/docs/elk/`](../docs/elk/README.md) — this module's `EndpointLogFieldTest`
  locks this module's field enum against that same template across the reactor. The field table is
  the guide's [§5.1](docs/GUIDE.md#51-log-fields).
- **Metrics:** the same six meters (`endpoint.logging.failopen`, `endpoint.logging.events`,
  `endpoint.logging.exchanges.open`, `endpoint.logging.correlation.id`, `endpoint.request/response.body.size`,
  `endpoint.request.body.read`),
  consumed from the host's `MeterRegistry`, never exported. The meter table is the guide's
  [§5.4](docs/GUIDE.md#54-meters).

## Deliberate stack differences

| Concern | Servlet twin | This module |
|---|---|---|
| Disposition vocabulary | `success` / `failure` / `timeout` | `success` / `failure` / **`cancelled`** (client disconnect — the reactive reality; there is no container async timeout) |
| `endpoint_async` field | emitted | **never emitted** — everything is asynchronous here, the flag would carry no information |
| Final-status emission | at `requestDestroyed`, after the error dispatch | at the terminal signal; for an error on an **uncommitted** response deferred to the commit callback, which sees the upstream handler's **rendered 500**. A commit that never happens leaves the exchange open on the gauge (the liveness signal) instead of logging a wrong status; a never-committed cancellation logs `-> -` and omits the status field |
| Chain-wide MDC | `endpoint_request_id`/`endpoint_method`/`endpoint_route` during the chain | Reactor **context** under the same keys; with `io.micrometer:context-propagation` on the classpath (an optional dependency — its presence is the opt-in) the auto-configuration registers matching `ThreadLocalAccessor`s and automatic propagation restores the identity into handler-side MDC, restoring parity. Both the accessors and the startup warning about the propagation mode are installed only while the Reactor variant owns the filter slot. Without the library: emission-scope MDC and the message inline only |
| Body tee | servlet stream/writer wrappers; `reset()`/`resetBuffer()` clears the capture | `DataBuffer` map-tee in request/response decorators (a non-advancing bounded copy out of each buffer; the original flows on untouched — pooled-buffer safe); no reset analog exists: emitted buffers are on their way to the client |

Everything else — fail-open including the wiring (`stage=wiring` degrades to pass-through), the
level/outcome decoupling, slow escalation, header sections with `includes`/`excludes`/`masked`/`unmasked`
(masked by default, ADR-0005) and the injectable `HeaderValueMasker` (default: the stable fingerprint),
the arrival line (`log-request-start`), count-only body measuring, path activation, the identity
contract of ADR-0002 (a conformant `traceparent`'s trace id **is** the request id, the wire stays
untouched; only a traceless exchange adopts or generates an `X-Correlation-Id` and echoes it back) —
behaves exactly as documented in the servlet twin's README and guide.

## The shared layer

The **byte-identical** part of the twins' shared layer (the `traceparent` parser with its fuzz target,
the injectable time/id interfaces, `reportQuietly`, the MDC keys and scope) lives in the internal
`limesium-common` module and is **inlined into this jar** by the Maven Shade plugin
([ADR-0003](../docs/adr/ADR-0003-limesium-common-inlined-by-shade.md)): consumers add exactly one
artifact, the published POM carries no extra dependency, and `limesium-common` itself is never
published.

Everything whose twin copies genuinely differ (field enum and metrics with their per-stack outcome
vocabulary, emitters, exchanges, properties with the reactive-only `variant`, body capture with its
own concurrency design) stays **deliberately duplicated**, per the original architecture-review
decision: one twin per host, standalone jars, contract-level code that changes rarely. For that
remainder every change is still a conscious port in both directions; the pins in `TwinContractTest` /
`EndpointLogFieldTest` / `EndpointLoggingReferenceConfigTest` catch *named* contract drift (meter
names, field names, configuration keys) — not behavioural drift inside near-identical code. A change
there must be ported consciously and verified in both modules.

## Usage

The host must be a **Spring Boot 4.x reactive web application** on Java 21 with an SLF4J 2.x binding;
the server (Reactor Netty by default) comes with the host's WebFlux starter, the module forces none.
Two optional libraries change the wiring rather than the output: `kotlinx-coroutines-reactor` with
`kotlinx-coroutines-slf4j` select the coroutine filter variant, and `io.micrometer:context-propagation`
enables handler-side MDC for the Reactor variant. The full list with the reasons is the guide's
[prerequisites table](docs/GUIDE.md#31-prerequisites); how the `endpoint_*` fields become visible in the
log output is [§3.8](docs/GUIDE.md#38-logging-backend-and-structured-output).

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>limesium-reactive-logging</artifactId>
    <version><!-- current release: see the badge below --></version>
</dependency>
```

[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/limesium-reactive-logging.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/eu.inqudium/limesium-reactive-logging)
— there is no BOM; the version is declared on the dependency itself. An application may carry both
twins: each auto-configures for its own web application type only, so the matching one activates and
the other stays inert; keep them at the same version, both jars inline the same shared classes.
`endpoint-logging.enabled=false` removes the module again without touching the classpath.

### Automatic wiring

The long form is the guide's [§3.3](docs/GUIDE.md#33-automatic-wiring).

In a reactive web application (`@ConditionalOnWebApplication(type = REACTIVE)`) the auto-configuration
registers exactly **one `EndpointLoggingFilter` bean**, and that is the whole wiring: WebFlux collects
every `WebFilter` bean from the application context and orders it by its `Ordered` contract — this
filter says `Ordered.HIGHEST_PRECEDENCE + 10`, early enough that the traceless correlation echo is set
before anything else runs and everything after it sees the exchange identity. There is nothing to
inject and nothing to build: every exchange the server dispatches passes the filter, and path activation
(`include-path-patterns`, `exclude-path-prefixes`) is evaluated inside it, byte-identical with the
servlet twin.

Which variant fills the slot is decided by the classpath: with `kotlinx-coroutines-reactor` **and**
`kotlinx-coroutines-slf4j` present — both optional dependencies of this module — the coroutine
auto-configuration runs first and registers `CoRequestLoggingWebFilter`, and the Reactor
auto-configuration backs off; otherwise `RequestLoggingWebFilter` registers. `endpoint-logging.variant`
makes the choice explicit when the classpath should not decide: `reactor` forces the Reactor variant
although the libraries are present (pulled in transitively by a Reactor-only host), `coroutine` requires
the coroutine variant and fails the context start with a message naming the missing libraries instead
of silently falling back.

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

Handler-side MDC is native in the coroutine variant (the chain runs inside `MDCContext`). For the
Reactor variant it is the one opt-in: `io.micrometer:context-propagation` on the classpath registers the
`endpoint_*` accessors, and `spring.reactor.context-propagation=auto` restores the identity around every
operator; without the property the auto-configuration warns once at startup. Without the library the
identity rides the Reactor context, the emission-scope MDC and the message inline only.

### Manual wiring

The long form — including the Boot-context variant with the auto-configuration switched off and the
rules for a hand-wired filter — is the guide's [§3.4](docs/GUIDE.md#34-manual-wiring).

The filter bean exists in every enabled reactive context; only its *pickup* depends on WebFlux
collecting `WebFilter` beans from a Boot application context. Add it yourself when the HTTP handler is
assembled without that context scan:

- **WebFlux assembled without Boot's auto-configuration** — an `HttpHandler` built by hand through
  `WebHttpHandlerBuilder` or `RouterFunctions.toHttpHandler(...)` with explicit `HandlerStrategies`.
  Those take the filters they are given; a bean in the context is not consulted.
- **Outside a Spring context** — a router function under test (`WebTestClient.bindToRouterFunction`),
  a library's own server. The filter is constructed directly; every default is public.

There is no "different order" case: the order is a property of the filter itself, not of a
registration, and it is the same in both variants.

```kotlin
val filter = RequestLoggingWebFilter(
    RequestLoggingProperties(),            // every default; or a copy(...) with the fields to change
    NanoTimeSource.SYSTEM,
    CorrelationIdGenerator.DEFAULT,
    SimpleMeterRegistry(),                 // or the registry the surrounding code owns
)
val httpHandler = RouterFunctions.toHttpHandler(
    router,
    HandlerStrategies.builder().webFilter(filter).build(),
)
```

Reuse one filter per `MeterRegistry` rather than constructing several: the meters are identified by
name, so all filters on one registry share one metrics owner and the `endpoint.logging.exchanges.open`
gauge reports the total across them ([§6.7](docs/GUIDE.md#67-one-metrics-instance-per-registry)).
Replacing the filter inside a Boot context is a different thing: a host-defined bean of **either**
variant satisfies the missing-bean condition, both auto-configurations back off, and WebFlux picks the
host's bean up like any other ([§3.7](docs/GUIDE.md#37-overriding-beans)) — as it does for the other
overridable beans, `NanoTimeSource`, `CorrelationIdGenerator` and `HeaderValueMasker` (how masked header
values render — a keyed HMAC, a fixed `***`). The context-propagation accessors are installed only while
a `RequestLoggingWebFilter` owns the slot.

### The exchange line

On the `endpoint-http-exchange` logger a completed exchange is one event. In a plain-text appender only the
message shows; it repeats the gist inline for exactly that case:

```
Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 parentSpanId=00f067aa0ba902b7]
```

With Spring Boot's structured logging (`logging.structured.format.console=ecs`) the same event is one
JSON document: the `endpoint_*` key-values and the MDC-carried identity become flat, typed top-level
fields next to the encoder's own envelope:

```json
{
  "@timestamp": "2026-09-04T13:54:58.534Z",
  "log": { "level": "INFO", "logger": "endpoint-http-exchange" },
  "process": { "pid": 4711, "thread": { "name": "reactor-http-nio-2" } },
  "service": { "name": "things-service" },
  "message": "Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 parentSpanId=00f067aa0ba902b7]",
  "endpoint_request_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "endpoint_method": "GET",
  "endpoint_route": "/api/things/42",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "parentSpanId": "00f067aa0ba902b7",
  "endpoint_outcome": "success",
  "endpoint_duration_ms": 17,
  "endpoint_request_method": "GET",
  "endpoint_response_status_code": 200,
  "endpoint_url_path": "/api/things/42",
  "endpoint_url_template": "/api/things/{id}",
  "ecs": { "version": "8.11" }
}
```

The trace keys and the trace suffix in the message appear only on a traced exchange — the caller's span
as `parentSpanId`, never as the local `spanId`; a traceless exchange carries the request id alone and has
echoed it to the client as `X-Correlation-Id`. A cancelled exchange logs `endpoint_outcome=cancelled`,
with `-> -` and no status field when the response was never committed. There is no `endpoint_async`
field on this stack. Optional fields (`endpoint_url_query`, `endpoint_slow`, the header and body
sections) are present only when they apply. Which encoder produces which shape — and why the default
console pattern shows none of the fields — is the guide's
[§3.8](docs/GUIDE.md#38-logging-backend-and-structured-output); the field family itself is documented
once, in the guide's [§5.1](docs/GUIDE.md#51-log-fields), and mapped by the component template in
[`/docs/elk/`](../docs/elk/README.md).

## Configuration (`endpoint-logging.*`)

Every property lives under the `endpoint-logging.*` namespace, identical to the servlet twin's by
construction plus this module's one `variant` key. The complete, commented reference with every key at
its default is this module's [`docs/endpoint-logging-reference.yml`](docs/endpoint-logging-reference.yml)
— copy the block and change only what you need; `EndpointLoggingReferenceConfigTest` binds it and the
repository-shared reference against the properties class and fails the build on any drift. The
properties are explained in the guide's [§4](docs/GUIDE.md#4-configuration): the property reference,
header sections, body logging and measuring, path activation, logger levels, validation at startup, and
example configurations. `endpoint-logging.enabled=false` removes the module without touching the
classpath.

## Metrics

The module's meters exist for one reason: a log line that was lost cannot report its own loss through
the same pipeline. Six meters, consumed from the host's `MeterRegistry` when one exists (actuator) and
never exported, form that independent channel — they answer whether exchange events are being lost
loudly (a fail-open counter by stage), lost silently (an open-exchange gauge whose baseline must return
towards zero — an error rendering that never commits keeps its exchange open there), or lost downstream
(an events counter to reconcile against the index), where each exchange's identity came from (trace,
header, or generated — a rising `generated` share means the gateway or sidecar stopped propagating), and
— opt-in — how large the bodies were and how far the application actually read the request body.
Rates, latencies and status distributions are deliberately left to Boot's own `http.server.requests`
and to the structured log fields.

Every meter with its type, tags and meaning is the guide's [§5.4](docs/GUIDE.md#54-meters); how to read
them together, with a suggested alert set, is [§5.5](docs/GUIDE.md#55-reading-the-meters-together). The
names are identical in both twins and pinned by `TwinContractTest`; the `outcome` tag of the events
counter carries this stack's `cancelled`.

## Kotlin coroutines

For coroutine WebFlux applications (`suspend fun` handlers) the module ships a second, coroutine-idiomatic
variant: **`CoRequestLoggingWebFilter`** (a `CoWebFilter`). Both variants delegate to the same internal
`ExchangeLifecycle`, so logging, configuration and metrics are identical by construction — the variant
choice is invisible to dashboards. The coroutine variant adds one thing natively: the chain runs inside
`MDCContext` with the `endpoint_*` identity, `CoWebFilter` publishes that coroutine context to the
handler invocation, and **every log line inside a suspend handler carries
`endpoint_request_id`/`endpoint_method`/`endpoint_route`** — the coroutine equivalent of the servlet
twin's chain-wide MDC scope, with no `context-propagation` dependency.

One boundary note: across the coroutine-to-Reactor bridge, kotlinx's stacktrace recovery may surface a
COPY of a handler exception (original as its cause); type, message and the reachable original — what
error handling classifies on — are preserved.
